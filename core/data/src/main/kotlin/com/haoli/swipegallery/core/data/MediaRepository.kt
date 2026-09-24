package com.haoli.swipegallery.core.data

import android.content.IntentSender
import android.graphics.Bitmap
import com.haoli.swipegallery.core.model.LibrarySnapshot
import com.haoli.swipegallery.core.model.MediaAlbum
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.core.model.MediaKind
import com.haoli.swipegallery.core.model.SortSpec
import com.haoli.swipegallery.core.model.StorageStats
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
     * [nameQuery] 非空时按文件名模糊匹配。
     * 结果会**剔除回收站里的项**，因此用户看到的就是「还没被删的」。
     */
    fun observeItems(
        kind: MediaKind,
        sort: SortSpec,
        albumId: Long? = null,
        nameQuery: String = "",
    ): Flow<List<MediaItem>>

    /** 该类型下的全部相册，按数量倒序。 */
    fun observeAlbums(kind: MediaKind): Flow<List<MediaAlbum>>

    /**
     * 存储占用统计。
     *
     * 单独把「回收站占用」列出来：应用回收站里的内容仍占着磁盘，
     * 不显示的话用户会以为删除已经释放了空间。
     */
    fun observeStats(): Flow<StorageStats>


    /** 网格用缩略图。图片与视频都走同一条路径。 */
    suspend fun thumbnail(uri: String, sizePx: Int): Bitmap?

    /** 查看器用全尺寸图（已按目标尺寸降采样并纠正 EXIF 方向）。 */
    suspend fun fullImage(uri: String, maxSizePx: Int): Bitmap?

    /**
     * 构造删除请求：**优先移入系统回收站，设备不支持时退回永久删除**。
     *
     * **只在用户于回收站页面明确点「删除」时调用。**
     * 滑卡删除绝不会走到这里 —— 那时没有二次确认，一旦系统行为不符预期就是静默丢数据。
     */
    fun purgeRequest(uris: List<String>): IntentSender?

    /** 从系统回收站取回（设备支持回收站时才有意义）。 */
    fun untrashRequest(uris: List<String>): IntentSender?

    /**
     * 回收站内容 —— **就是系统回收站**（`IS_TRASHED = 1`）。
     *
     * 刻意不做「应用自己的回收站」：那样会出现两套互不相干的回收站，
     * 在应用里删掉的东西在手机相册的回收站里看不到，反之亦然。
     * 这里直接读系统回收站，两边天然一致。
     *
     * 设备不支持回收站时返回空列表，界面据此提示。
     */
    fun observeTrash(): Flow<List<MediaItem>>

    /**
     * 构造「移入回收站」请求。滑卡会话结束时整批调用一次。
     *
     * 用系统回收站而不是永久删除，是为了让内容在手机相册里也能找到并取回。
     */
    fun trashRequest(uris: List<String>): IntentSender?

    /**
     * 构造「移动到指定相册」所需的写入授权请求。
     *
     * 移动要改文件的 RELATIVE_PATH，必须先拿到系统授予的写权限。
     * 返回 null 表示没有待移动项，调用方应跳过授权直接结束。
     */
    fun moveRequest(uris: List<String>): IntentSender?

    /**
     * 执行移动。**必须在 [moveRequest] 得到用户同意之后调用。**
     * 返回成功移动的条数。
     */
    suspend fun moveToAlbum(uris: List<String>, targetRelativePath: String): Int
}
