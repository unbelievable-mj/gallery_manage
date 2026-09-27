package com.haoli.swipegallery.core.data.motion

/**
 * 动态照片里内嵌视频的定位逻辑。
 *
 * 单独抽出来是为了**能被单元测试覆盖**：这里没有任何 Android 依赖，
 * 只对一段字节做判断，可以用构造的样本跑遍各厂商的格式差异。
 * [MotionPhotoExtractor] 负责读文件、调它、再写缓存。
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
 * ## 已知的兼容性边界（都是推断，未在真机矩阵上验证）
 *
 * - **HEIC / AVIF 动态照片**：这类文件本身也是 ISOBMFF，开头就有 `ftyp`。
 *   但它的 brand 是 `heic` / `mif1` / `msf1`，不在已知列表里，会被正确排除；
 *   而追加在末尾的视频 brand 是 `isom` / `mp42` 一类，能命中。
 * - **视频不是 MP4**：规范要求必须是 AVC / HEVC / AV1 的 MP4，
 *   所以 `ftyp` 一定存在。若某厂商用了别的容器，这里会漏掉。
 * - **`ftyp` 不是视频的第一个盒**：规范要求 MP4 以 `ftyp` 开头，
 *   这条如果被违反也会漏掉。
 * - **视频体积超出扫描窗口**：见 [MotionPhotoExtractor] 的窗口常量。
 */
internal object MotionPhotoScanner {

    /**
     * 在文件尾部缓冲区里找内嵌视频的起点，返回相对缓冲区的偏移。
     *
     * 从后往前找，取**最后一个**匹配 —— 视频在文件最末尾，
     * 它的 `ftyp` 也就是最后一次出现。取最后一个同时避开了
     * HEIC 主图开头那个 `ftyp`（那个 brand 也不在已知列表，双重保险）。
     *
     * 返回 null 表示没找到，调用方按普通图片处理。
     */
    fun findVideoStart(tail: ByteArray): Int? {
        for (i in tail.size - FTYP.size downTo 4) {
            if (!matchesAt(tail, i, FTYP)) continue

            // 紧邻其前的 4 字节 = 盒长度（大端）。
            // 这一条用来排除图片数据里恰好出现 "ftyp" 四个字节的情况：
            // 巧合很难同时满足「前面 4 字节是个合理的小整数」。
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

    const val MIN_FTYP_BOX = 8
    const val MAX_FTYP_BOX = 4096

    val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)

    /**
     * 已知的 MP4 brand。
     *
     * 刻意**不含** `heic` / `mif1` / `msf1` / `avif` ——
     * 那些是 HEIC / AVIF 主图自身的 brand，收进来会把主图误判成视频。
     */
    val KNOWN_BRANDS = setOf(
        "isom", "iso2", "iso4", "iso5", "iso6",
        "mp41", "mp42", "avc1", "M4V ", "M4A ",
        "3gp4", "3gp5", "qt  ", "dash", "msdh",
    )
}
