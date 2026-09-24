package com.haoli.swipegallery.core.model

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

    /** 下滑「保留」时文件的落点。 */
    val keepTarget: KeepTarget = KeepTarget.ORIGINAL_ALBUM,

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
