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
) {
    val defaultSort: SortSpec
        get() = SortSpec(field = defaultSortField, direction = defaultSortDirection)

    companion object {
        const val DEFAULT_PRELOAD_COUNT = 3
        const val MIN_PRELOAD_COUNT = 1
        const val MAX_PRELOAD_COUNT = 5

        val PRELOAD_RANGE = MIN_PRELOAD_COUNT..MAX_PRELOAD_COUNT

        /** 把任意输入夹到合法范围，避免坏数据导致预加载失控。 */
        fun sanitizePreloadCount(value: Int): Int =
            value.coerceIn(MIN_PRELOAD_COUNT, MAX_PRELOAD_COUNT)
    }
}
