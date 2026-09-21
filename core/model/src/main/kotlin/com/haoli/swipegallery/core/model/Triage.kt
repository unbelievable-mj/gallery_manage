package com.haoli.swipegallery.core.model

/** 排序字段。用户可在设置里自选，排序结果决定滑卡队列的顺序。 */
enum class SortField(val label: String) {
    DATE_TAKEN("拍摄时间"),
    DATE_MODIFIED("修改时间"),
    SIZE("文件占用空间"),
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

/** 下滑「保留」时文件的落点。默认保留在原相册，即文件零改动。 */
enum class KeepTarget(val label: String) {
    /** 仅从本次队列移除，文件完全不动 */
    ORIGINAL_ALBUM("保留在原相册"),
    /** 修改 RELATIVE_PATH 移动到指定相册 */
    SPECIFIC_ALBUM("移动到指定相册"),
    /** 通过 SAF 复制到用户选定的目录，原文件保留 */
    SAF_DIRECTORY("复制到自选目录"),
    /** 标记为收藏 */
    FAVORITE("标记为收藏"),
}

/**
 * 一次滑卡决策的记录。
 *
 * 这是撤销能力的载体：所有决策都进入操作栈，
 * 撤销即反向执行栈顶记录。
 */
sealed interface TriageAction {
    val itemId: Long
    val timestampMillis: Long

    data class Deleted(
        override val itemId: Long,
        override val timestampMillis: Long,
    ) : TriageAction

    data class Kept(
        override val itemId: Long,
        override val timestampMillis: Long,
        val target: KeepTarget,
    ) : TriageAction

    data class Moved(
        override val itemId: Long,
        override val timestampMillis: Long,
        val fromAlbum: String,
        val toAlbum: String,
    ) : TriageAction
}
