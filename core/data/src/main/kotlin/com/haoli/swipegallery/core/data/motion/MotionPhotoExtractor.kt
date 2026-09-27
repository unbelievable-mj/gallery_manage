package com.haoli.swipegallery.core.data.motion

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 从动态照片（Live / Motion Photo）里取出内嵌的视频。
 *
 * ## 为什么不去解析 XMP
 *
 * 官方规范给的做法是读 XMP：新版看 `Container:Item:Length`，
 * 旧版（Microvideo V1）看 `Camera:MicroVideoOffset`。
 * 但各厂商实现不一致 —— 这正是「不同手机品牌表现不一样」的原因。
 *
 * 而这两套做法有个**共同前提**：视频是作为 MP4 **追加在主图之后、
 * 且位于文件最末尾**。所以这里反过来做：直接在文件尾部找 MP4 的
 * `ftyp` 盒，从那里截到文件结尾。新旧规范都能覆盖，也不依赖厂商字段。
 *
 * ## 校验
 *
 * 光搜 `ftyp` 四个字节可能命中图片数据里的巧合，所以还要求：
 * - 紧邻其前的 4 字节按大端解释是一个合理的盒长度（8..4096）
 * - 紧随其后的 4 字节是已知的 MP4 brand
 *
 * 两个条件同时满足才算数，误判概率极低。
 */
@Singleton
class MotionPhotoExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 把内嵌视频抽到缓存目录，返回文件路径。
     *
     * 不是动态照片（或解析失败）时返回 null —— 调用方据此退回静态显示。
     * 失败是正常路径而不是异常：绝大多数图片都不是动态照片。
     */
    suspend fun extract(uri: String): String? = withContext(Dispatchers.IO) {
        runCatching { extractInternal(uri) }
            .onFailure { Timber.w(it, "解析动态照片失败: %s", uri) }
            .getOrNull()
    }

    /** 删除此前抽出的临时文件。 */
    suspend fun cleanup(path: String) = withContext(Dispatchers.IO) {
        runCatching { File(path).delete() }
        Unit
    }

    private fun extractInternal(uri: String): String? {
        val parsed = Uri.parse(uri)
        val descriptor = context.contentResolver.openFileDescriptor(parsed, "r") ?: return null

        descriptor.use { pfd ->
            val fileSize = pfd.statSize
            if (fileSize <= 0L) return null

            val tailLength = minOf(fileSize, MAX_TAIL_BYTES).toInt()
            val tailStart = fileSize - tailLength
            val tail = ByteArray(tailLength)

            // 用 FileInputStream 而不是 RandomAccessFile：
            // RandomAccessFile 没有接受 FileDescriptor 的构造函数，
            // 而 FileInputStream 有，且能通过 channel 精确定位。
            FileInputStream(pfd.fileDescriptor).use { stream ->
                stream.channel.position(tailStart)
                DataInputStream(stream).readFully(tail)
            }

            val videoOffsetInTail = findVideoStart(tail) ?: return null
            val videoStart = tailStart + videoOffsetInTail
            val videoLength = fileSize - videoStart

            // 太短的不可能是视频；太长的话可能是误判，宁可放弃
            if (videoLength < MIN_VIDEO_BYTES || videoLength > MAX_VIDEO_BYTES) return null

            val outFile = File(cacheDir(), cacheNameFor(parsed))
            FileInputStream(pfd.fileDescriptor).use { stream ->
                stream.channel.position(videoStart)
                val input = DataInputStream(stream)
                outFile.outputStream().use { out ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var remaining = videoLength
                    while (remaining > 0L) {
                        val want = minOf(remaining, buffer.size.toLong()).toInt()
                        val read = input.read(buffer, 0, want)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }

            return outFile.takeIf { it.length() > 0L }?.absolutePath
        }
    }

    /**
     * 在文件尾部缓冲区里找 MP4 起点，返回相对缓冲区的偏移。
     *
     * 从后往前找，取**最后一个**匹配 —— 视频在文件最末尾，
     * 它的 `ftyp` 也就是最后一次出现。
     */
    private fun findVideoStart(tail: ByteArray): Int? {
        for (i in tail.size - FTYP.size downTo 4) {
            if (!matchesAt(tail, i, FTYP)) continue

            // 紧邻其前的 4 字节 = 盒长度（大端）
            val boxSize = readInt(tail, i - 4)
            if (boxSize < MIN_FTYP_BOX || boxSize > MAX_FTYP_BOX) continue

            // 紧随其后的 4 字节 = brand
            if (i + FTYP.size + 4 > tail.size) continue
            val brand = String(tail, i + FTYP.size, 4, Charsets.US_ASCII)
            if (brand !in KNOWN_BRANDS) continue

            // 命中：视频从盒长度字段开始
            return i - 4
        }
        return null
    }

    private fun matchesAt(data: ByteArray, offset: Int, needle: ByteArray): Boolean {
        if (offset < 0 || offset + needle.size > data.size) return false
        for (k in needle.indices) {
            if (data[offset + k] != needle[k]) return false
        }
        return true
    }

    private fun readInt(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > data.size) return -1
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun cacheDir(): File =
        File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }

    private fun cacheNameFor(uri: Uri): String =
        "motion_" + (uri.lastPathSegment ?: "unknown").hashCode().toString(16) + ".mp4"

    private companion object {
        const val CACHE_DIR_NAME = "motion_photos"

        /** 只在文件尾部这么多字节里找。视频通常几秒，几 MB 足够覆盖。 */
        const val MAX_TAIL_BYTES = 8L * 1024 * 1024

        const val COPY_BUFFER_BYTES = 64 * 1024

        const val MIN_VIDEO_BYTES = 32L * 1024
        const val MAX_VIDEO_BYTES = 64L * 1024 * 1024

        const val MIN_FTYP_BOX = 8
        const val MAX_FTYP_BOX = 4096

        val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)

        /** 已知的 MP4 brand。多列几个，覆盖不同厂商的输出。 */
        val KNOWN_BRANDS = setOf(
            "isom", "iso2", "iso4", "iso5", "iso6",
            "mp41", "mp42", "avc1", "M4V ", "M4A ",
            "3gp4", "3gp5", "qt  ", "dash", "msdh",
        )
    }
}
