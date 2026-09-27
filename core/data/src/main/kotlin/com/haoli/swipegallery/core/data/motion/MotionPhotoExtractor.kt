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

            val videoOffsetInTail = MotionPhotoScanner.findVideoStart(tail) ?: return null
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

    private fun cacheDir(): File =
        File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }

    private fun cacheNameFor(uri: Uri): String =
        "motion_" + (uri.lastPathSegment ?: "unknown").hashCode().toString(16) + ".mp4"

    private companion object {
        const val CACHE_DIR_NAME = "motion_photos"

        /**
         * 只在文件尾部这么多字节里找。
         *
         * 动态照片的视频通常只有几秒（1–3MB），16MB 留了足够余量。
         * 再大就没必要了 —— 缓冲区是实打实分配的内存。
         */
        const val MAX_TAIL_BYTES = 16L * 1024 * 1024

        const val COPY_BUFFER_BYTES = 64 * 1024

        const val MIN_VIDEO_BYTES = 32L * 1024
        const val MAX_VIDEO_BYTES = 64L * 1024 * 1024


    }
}
