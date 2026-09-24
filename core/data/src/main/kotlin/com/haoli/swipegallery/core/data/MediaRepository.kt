package com.haoli.swipegallery.core.data

import android.content.IntentSender
import android.graphics.Bitmap
import com.haoli.swipegallery.core.model.LibrarySnapshot
import com.haoli.swipegallery.core.model.MediaAlbum
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.core.model.MediaKind
import com.haoli.swipegallery.core.model.SortSpec
import kotlinx.coroutines.flow.Flow

/**
 * 媒体数据的唯一出入口。
 *
 * UI 层只与它打交道，不直接接触 ContentResolver。
 * 读操作（查询、解码）与写操作（删除）都在这里收敛，
 * 具体实现见 [MediaStoreMediaRepository]。
 */
interface MediaRepository {

    /** 媒体库概览：图片/视频数量与总占用。 */
    fun observeSnapshot(): Flow<LibrarySnapshot>

    /**
     * 按类型与排序规则取媒体列表。滑卡队列由它生成。
     * [albumId] 非空时只返回该相册内的媒体。
     */
    fun observeItems(
        kind: MediaKind,
        sort: SortSpec,
        albumId: Long? = null,
    ): Flow<List<MediaItem>>

    /** 该类型下的全部相册，按数量倒序。 */
    fun observeAlbums(kind: MediaKind): Flow<List<MediaAlbum>>

    /** 网格用缩略图。图片与视频都走同一条路径。 */
    suspend fun thumbnail(uri: String, sizePx: Int): Bitmap?

    /** 查看器用全尺寸图（已按目标尺寸降采样并纠正 EXIF 方向）。 */
    suspend fun fullImage(uri: String, maxSizePx: Int): Bitmap?

    /** 构造批量移入系统回收站的请求（删除阶段二）。 */
    fun trashRequest(uris: List<String>): IntentSender?

    /** 构造从系统回收站还原的请求（撤销窗口过期后的反悔）。 */
    fun untrashRequest(uris: List<String>): IntentSender?

    /** 构造永久删除请求，跳过回收站。 */
    fun deleteRequest(uris: List<String>): IntentSender?
}
