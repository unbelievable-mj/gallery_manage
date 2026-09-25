package com.haoli.swipegallery.core.model

/**
 * 「下滑保留」的目标相册。
 *
 * 存了 id、名字与目录路径三样：id 用于界面回显选中态，
 * 名字用于提示文案，路径才是移动时真正写进 `RELATIVE_PATH` 的值。
 */
data class MoveTarget(
    val albumId: Long,
    val albumName: String,
    val relativePath: String,
)

/**
 * 用户可配置项。
 *
 * 全部带默认值，因此首次启动不需要任何初始化逻辑 —— 读不到值时直接用默认，
 * 这也是「默认缓存三张」这条需求的落地方式。
 */
data class AppSettings(
    /**
     * 查看器预加载张数。
     *
     * 越大滑得越顺，但内存峰值也越高（每张全尺寸图按 2048px 解码约 16MB）。
     */
    val preloadCount: Int = DEFAULT_PRELOAD_COUNT,

    /**
     * 下滑「保留」的落点。
     *
     * `null` 表示保留在原相册 —— 文件零改动，只是从本次队列里移出。
     * 非 null 时下滑会把文件移动到这个相册。
     *
     * 之所以要做成可配置：下滑与左滑在「保留在原相册」时对文件的效果完全相同，
     * 手势重合等于浪费。给了目标相册之后，下滑才成为真正有效的归档动作。
     */
    val moveTarget: MoveTarget? = null,

    /** 打开图库时的默认排序。 */
    val defaultSortField: SortField = SortField.DATE_TAKEN,
    val defaultSortDirection: SortDirection = SortDirection.DESC,

    /**
     * 查重阈值：文件大小相差不超过这个字节数就算「疑似重复」。
     *
     * 默认 20KB —— 同一张图被不同应用重新压缩保存，体积往往只差几 KB 到几十 KB。
     */
    val duplicateThresholdBytes: Long = DEFAULT_DUPLICATE_THRESHOLD_BYTES,
) {
    val defaultSort: SortSpec
        get() = SortSpec(field = defaultSortField, direction = defaultSortDirection)

    companion object {
        const val DEFAULT_PRELOAD_COUNT = 3
        const val MIN_PRELOAD_COUNT = 1
        const val MAX_PRELOAD_COUNT = 5

        val PRELOAD_RANGE = MIN_PRELOAD_COUNT..MAX_PRELOAD_COUNT

        const val DEFAULT_DUPLICATE_THRESHOLD_BYTES = 20L * 1024L

        /**
         * 可选的阈值档位。
         *
         * 用固定档位而不是滑杆：这个值用户很难有「精确」的直觉，
         * 给几个差别明显的选项反而更好选。
         *
         * 档位偏小端：实际用下来，同一张图被不同应用重新压缩保存，
         * 体积差通常在几百字节到几 KB，20KB 以上基本只能匹配到连拍。
         */
        val DUPLICATE_THRESHOLD_OPTIONS = listOf(
            100L to "0.1 KB",
            1L * 1024L to "1 KB",
            5L * 1024L to "5 KB",
            20L * 1024L to "20 KB",
            50L * 1024L to "50 KB",
        )

        /** 把任意输入夹到合法范围，避免坏数据导致分组粒度失控。 */
        fun sanitizeDuplicateThreshold(value: Long): Long {
            val min = DUPLICATE_THRESHOLD_OPTIONS.first().first
            val max = DUPLICATE_THRESHOLD_OPTIONS.last().first
            return value.coerceIn(min, max)
        }

        /** 把任意输入夹到合法范围，避免坏数据导致预加载失控。 */
        fun sanitizePreloadCount(value: Int): Int =
            value.coerceIn(MIN_PRELOAD_COUNT, MAX_PRELOAD_COUNT)
    }
}
