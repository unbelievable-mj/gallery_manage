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

/**
 * 一个相册（MediaStore 里叫 bucket）。
 *
 * 用户需求里明确提到「可以选择某个相册单独处理」，
 * 所以相册是滑卡范围的第一等概念，而不是一个筛选附属品。
 */
data class MediaAlbum(
    val id: Long,
    val name: String,
    val itemCount: Int,
    val kind: MediaKind,
    /** 该相册内所有媒体的字节数之和，统计页用它排序。 */
    val totalBytes: Long = 0L,
    /**
     * 相册对应的目录相对路径，形如 `DCIM/Camera/`。
     *
     * 「移动到指定相册」需要它 —— 移动本质上是把文件的 RELATIVE_PATH
     * 改成目标相册的这个值。
     */
    val relativePath: String = "",
)

/** 单个相册的占用条目，统计页展示用。 */
data class AlbumUsage(
    val albumId: Long,
    val albumName: String,
    val kind: MediaKind,
    val itemCount: Int,
    val bytes: Long,
)

/**
 * 存储占用统计。
 *
 * 「回收站占用」单列是因为它最容易被忽略：应用回收站里的内容
 * 仍占着磁盘，不显示出来的话用户会以为删除已经释放了空间。
 */
data class StorageStats(
    val imageCount: Int = 0,
    val imageBytes: Long = 0L,
    val videoCount: Int = 0,
    val videoBytes: Long = 0L,
    val trashedCount: Int = 0,
    val trashedBytes: Long = 0L,
    val albums: List<AlbumUsage> = emptyList(),
) {
    val totalBytes: Long get() = imageBytes + videoBytes
    val totalCount: Int get() = imageCount + videoCount
    val isEmpty: Boolean get() = totalCount == 0 && trashedCount == 0
}
