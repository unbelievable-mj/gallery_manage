package com.haoli.swipegallery.core.model

/** 媒体类型。对应顶部「图片 / 视频」切换。 */
enum class MediaKind {
    IMAGE,
    VIDEO,
}

/**
 * 一条媒体记录。
 *
 * 字段直接对应 MediaStore 的投影列，避免数据层再做一次映射。
 * 时间统一使用毫秒时间戳；MediaStore 中缺失的列允许为 null。
 */
data class MediaItem(
    val id: Long,
    val uri: String,
    val displayName: String,
    val kind: MediaKind,
    val sizeBytes: Long,
    /** DATE_TAKEN，部分机型/来源缺失，回退到 [dateAddedMillis] */
    val dateTakenMillis: Long?,
    val dateAddedMillis: Long,
    val width: Int,
    val height: Int,
    /** 仅视频有值 */
    val durationMillis: Long?,
    val albumId: Long,
    val albumName: String,
    val relativePath: String?,
) {
    /** 排序与信息栏展示统一用这个，屏蔽 DATE_TAKEN 缺失的差异。 */
    val effectiveDateMillis: Long
        get() = dateTakenMillis ?: dateAddedMillis
}

/** 当前媒体库的概览快照，用于统计页与首页。 */
data class LibrarySnapshot(
    val imageCount: Int,
    val videoCount: Int,
    val totalSizeBytes: Long,
    /**
     * M0 骨架阶段数据层尚未接入 MediaStore，返回的是占位值。
     * UI 据此提示用户，而不是把 0 当成真实结果展示。
     */
    val isStub: Boolean = false,
)
