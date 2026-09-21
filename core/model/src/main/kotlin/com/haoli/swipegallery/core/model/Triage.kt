package com.haoli.swipegallery.core.model

/** 排序字段。用户可在首屏切换，排序结果决定滑卡队列的顺序。 */
enum class SortField(val label: String) {
    DATE_TAKEN("拍摄时间"),
    DATE_MODIFIED("修改时间"),
    SIZE("文件大小"),
    DISPLAY_NAME("文件名"),
    DURATION("视频时长"),
}

enum class SortDirection(val label: String) {
    ASC("升序"),
    DESC("降序"),
}

data class SortSpec(
    val field: SortField = SortField.DATE_TAKEN,
    val direction: SortDirection = SortDirection.DESC,
)

/** 本次滑卡处理的范围。 */
sealed interface TriageScope {
    data class All(val kind: MediaKind) : TriageScope
    data class Album(val albumId: Long, val albumName: String) : TriageScope
    data class DateRange(val fromMillis: Long, val toMillis: Long) : TriageScope
    data class MinSize(val thresholdBytes: Long) : TriageScope
}

/**
 * 下滑「保留」时文件的落点。
 *
 * 默认 [ORIGINAL_ALBUM] —— 文件零改动，仅从本次队列移除。
 * 这是「上滑删、下滑留」这套纯二选一语义能成立的关键：
 * 保留下来的文件不产生任何副作用，用户不必为「留」这件事承担风险。
 */
enum class KeepTarget(val label: String) {
    ORIGINAL_ALBUM("保留在原相册"),
    SPECIFIC_ALBUM("移动到指定相册"),
    SAF_DIRECTORY("复制到自选目录"),
    FAVORITE("标记为收藏"),
}

/**
 * 一次滑卡决策的记录。
 *
 * 携带 [MediaItem] 与 [originalIndex] 而非仅 id：撤销时要把它放回队列的原始位置，
 * 只留 id 的话还得额外维护一张 id → 位置的反查表。
 */
sealed interface TriageAction {
    val item: MediaItem
    val originalIndex: Int
    val timestampMillis: Long

    data class Deleted(
        override val item: MediaItem,
        override val originalIndex: Int,
        override val timestampMillis: Long,
    ) : TriageAction

    data class Kept(
        override val item: MediaItem,
        override val originalIndex: Int,
        override val timestampMillis: Long,
        val target: KeepTarget = KeepTarget.ORIGINAL_ALBUM,
    ) : TriageAction
}
