package com.haoli.swipegallery.core.data.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 动态照片定位逻辑的单元测试。
 *
 * 这里**用构造的字节样本覆盖各厂商的格式差异** —— 真机矩阵测不了，
 * 但算法本身能不能处理这些变体是可以在 JVM 上验证的。
 *
 * 覆盖的是「找得到 / 找得对 / 不会误判」三件事，
 * 不涉及读文件与写缓存（那部分依赖 Android 运行时）。
 */
class MotionPhotoScannerTest {

    // ---------------------------------------------------------------- 正常情况

    @Test
    fun `普通 JPEG 不含有内嵌视频`() {
        val jpeg = jpegBytes(1024)
        assertNull(MotionPhotoScanner.findVideoStart(jpeg))
    }

    @Test
    fun `JPEG 后追加 MP4 能定位到视频起点`() {
        val jpeg = jpegBytes(2048)
        val mp4 = mp4Bytes("isom", 512)
        val tail = jpeg + mp4

        assertEquals(jpeg.size, MotionPhotoScanner.findVideoStart(tail))
    }

    /**
     * 各厂商用的 brand 不同 —— 这是「不同手机表现不一样」的直接来源之一。
     * 每一个都必须能命中。
     */
    @Test
    fun `各厂商常见的 brand 都能识别`() {
        val brands = listOf(
            "isom",  // 最常见
            "mp42",  // 通用 MP4
            "avc1",  // H.264
            "M4V ",  // Apple 系
            "qt  ",  // QuickTime
            "3gp4",  // 部分安卓机
            "msdh",  // 分片 MP4
        )

        brands.forEach { brand ->
            val jpeg = jpegBytes(512)
            val tail = jpeg + mp4Bytes(brand, 256)
            assertEquals(
                "brand=$brand 应当被识别",
                jpeg.size,
                MotionPhotoScanner.findVideoStart(tail),
            )
        }
    }

    // ---------------------------------------------------------------- 不该误判

    @Test
    fun `图片数据里恰好出现 ftyp 字样时不会误判`() {
        // 构造：随便一段数据里出现 "ftyp"，但前面 4 字节不是合理的盒长度
        val noise = ByteArray(512) { 0x41 }
        val ftyp = "ftyp".toByteArray(Charsets.US_ASCII)
        ftyp.copyInto(noise, 256)
        // 前面 4 字节全是 0x41，按大端是个巨大的数，超出合理盒长度范围

        assertNull(MotionPhotoScanner.findVideoStart(noise))
    }

    @Test
    fun `HEIC 主图自身的 ftyp 不会被当成视频`() {
        // HEIC / AVIF 也是 ISOBMFF，开头就有 ftyp，但 brand 是 heic / mif1 / msf1。
        // 这些不在已知列表里，必须排除 —— 否则会把整张 HEIC 当成视频截出来。
        listOf("heic", "heix", "mif1", "msf1", "avif").forEach { brand ->
            val heic = mp4Bytes(brand, 256) + ByteArray(512)
            assertNull("brand=$brand 不该被识别", MotionPhotoScanner.findVideoStart(heic))
        }
    }

    @Test
    fun `盒长度不合理时跳过`() {
        // 长度 4（小于最小合法值 8）
        val tooSmall = ByteArray(64)
        writeInt(tooSmall, 0, 4)
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(tooSmall, 4)
        "isom".toByteArray(Charsets.US_ASCII).copyInto(tooSmall, 8)
        assertNull(MotionPhotoScanner.findVideoStart(tooSmall))

        // 长度 100000（大于最大合理值 4096）
        val tooLarge = ByteArray(64)
        writeInt(tooLarge, 0, 100_000)
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(tooLarge, 4)
        "isom".toByteArray(Charsets.US_ASCII).copyInto(tooLarge, 8)
        assertNull(MotionPhotoScanner.findVideoStart(tooLarge))
    }

    @Test
    fun `太短的缓冲区不会越界`() {
        assertNull(MotionPhotoScanner.findVideoStart(ByteArray(0)))
        assertNull(MotionPhotoScanner.findVideoStart(ByteArray(3)))
        assertNull(MotionPhotoScanner.findVideoStart(ByteArray(7)))
    }

    // ---------------------------------------------------------------- 取最后一个匹配

    @Test
    fun `存在多个 ftyp 时取最后一个`() {
        // 视频在文件最末尾，所以它的 ftyp 是最后一次出现。
        // 若取第一个，可能截到图片中间那段，导出的视频是坏的。
        val first = mp4Bytes("isom", 64)
        val middle = jpegBytes(256)
        val video = mp4Bytes("mp42", 512)
        val tail = first + middle + video

        val expected = first.size + middle.size
        assertEquals(expected, MotionPhotoScanner.findVideoStart(tail))
    }

    // ---------------------------------------------------------------- 辅助

    /** 造一段以 JPEG 的 SOI 开头、EOI 结尾的假图片数据。 */
    private fun jpegBytes(size: Int): ByteArray {
        val data = ByteArray(size) { 0x20 }
        if (size >= 2) {
            data[0] = 0xFF.toByte()
            data[1] = 0xD8.toByte()
            data[size - 2] = 0xFF.toByte()
            data[size - 1] = 0xD9.toByte()
        }
        return data
    }

    /** 造一个最小的 MP4 片段：ftyp 盒 + 一些填充。 */
    private fun mp4Bytes(brand: String, totalSize: Int): ByteArray {
        val data = ByteArray(totalSize)
        val boxSize = 24
        writeInt(data, 0, boxSize)
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(data, 4)
        brand.toByteArray(Charsets.US_ASCII).copyInto(data, 8)
        return data
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
