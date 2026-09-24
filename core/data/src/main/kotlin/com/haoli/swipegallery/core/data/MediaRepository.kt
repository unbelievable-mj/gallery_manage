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
     * 结果会**剔除回收站里的项**，因此用户看到的就是「还没被删的」。
     */
    fun observeItems(
        kind: MediaKind,
        sort: SortSpec,
        albumId: Long? = null,
    ): Flow<List<MediaItem>>

    /** 该类型下的全部相册，按数量倒序。 */
    fun observeAlbums(kind: MediaKind): Flow<List<MediaAlbum>>

    /**
     * 回收站内容。
     *
     * 这是**应用自己的**回收站，不是系统回收站。原因见 `TrashEntity` 的注释：
     * 系统回收站在部分 ROM 上不可靠，把删除绑上去等于把用户数据交给运气。
     */
    fun observeTrash(): Flow<List<MediaItem>>

    /** 把内容记入回收站。文件本身不动，因此瞬时完成、绝对可逆。 */
    suspend fun addToTrash(items: List<MediaItem>)

    /** 从回收站取回：只删掉记录，文件从未被动过。 */
    suspend fun restoreFromTrash(mediaIds: List<Long>)

    /** 网格用缩略图。图片与视频都走同一条路径。 */
    suspend fun thumbnail(uri: String, sizePx: Int): Bitmap?

    /** 查看器用全尺寸图（已按目标尺寸降采样并纠正 EXIF 方向）。 */
    suspend fun fullImage(uri: String, maxSizePx: Int): Bitmap?

    /**
     * 构造永久删除请求。跳过回收站，不可恢复。
     *
     * **只在用户于回收站页面明确选择「彻底删除」时调用。**
     * 滑卡删除绝不会走到这里 —— 那是数据丢失的唯一来源。
     */
    fun deleteRequest(uris: List<String>): IntentSender?
}
