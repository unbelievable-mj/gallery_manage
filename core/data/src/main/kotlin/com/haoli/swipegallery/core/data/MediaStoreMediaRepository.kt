package com.haoli.swipegallery.core.data

import android.content.IntentSender
import android.graphics.Bitmap
import com.haoli.swipegallery.core.data.media.FullImageLoader
import com.haoli.swipegallery.core.data.media.MediaOperator
import com.haoli.swipegallery.core.data.media.MediaStoreDataSource
import com.haoli.swipegallery.core.data.media.ThumbnailLoader
import com.haoli.swipegallery.core.model.AlbumUsage
import com.haoli.swipegallery.core.model.LibrarySnapshot
import com.haoli.swipegallery.core.model.MediaAlbum
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.core.model.MediaKind
import com.haoli.swipegallery.core.model.SortSpec
import com.haoli.swipegallery.core.model.StorageStats
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * 基于 MediaStore 的实现。
 *
 * 三件事：查询（[MediaStoreDataSource]）、解码（[ThumbnailLoader] / [FullImageLoader]）、
 * 写操作（[MediaOperator]）。回收站直接读系统回收站，不另存一份。
 */
@Singleton
class MediaStoreMediaRepository @Inject constructor(
    private val dataSource: MediaStoreDataSource,
    private val thumbnailLoader: ThumbnailLoader,
    private val fullImageLoader: FullImageLoader,
    private val mediaOperator: MediaOperator,
) : MediaRepository {

    override fun observeSnapshot(): Flow<LibrarySnapshot> = flow {
        emit(dataSource.snapshot())
    }.flowOn(Dispatchers.IO)

    override fun observeItems(
        kind: MediaKind,
        sort: SortSpec,
        albumId: Long?,
    ): Flow<List<MediaItem>> = flow {
        // 不需要额外剔除已删项：查询本身就带 IS_TRASHED = 0，
        // 系统回收站里的内容不会出现在结果中
        emit(dataSource.items(kind, sort, albumId))
    }.flowOn(Dispatchers.IO)

    override fun observeAlbums(kind: MediaKind): Flow<List<MediaAlbum>> = flow {
        emit(dataSource.albums(kind))
    }.flowOn(Dispatchers.IO)

    override fun observeStats(): Flow<StorageStats> = flow {
        // 相册查询已经带了数量与占用，按类型求和就是各类型的小计，
        // 不必再单独扫一遍媒体表
        val imageAlbums = dataSource.albums(MediaKind.IMAGE)
        val videoAlbums = dataSource.albums(MediaKind.VIDEO)
        val trashed = dataSource.trashedItems()

        emit(
            StorageStats(
                imageCount = imageAlbums.sumOf { it.itemCount },
                imageBytes = imageAlbums.sumOf { it.totalBytes },
                videoCount = videoAlbums.sumOf { it.itemCount },
                videoBytes = videoAlbums.sumOf { it.totalBytes },
                trashedCount = trashed.size,
                trashedBytes = trashed.sumOf { it.sizeBytes },
                albums = (imageAlbums + videoAlbums)
                    .map {
                        AlbumUsage(
                            albumId = it.id,
                            albumName = it.name,
                            kind = it.kind,
                            itemCount = it.itemCount,
                            bytes = it.totalBytes,
                        )
                    }
                    .sortedByDescending { it.bytes },
            )
        )
    }.flowOn(Dispatchers.IO)

    override fun observeTrash(): Flow<List<MediaItem>> = flow {
        emit(dataSource.trashedItems())
    }.flowOn(Dispatchers.IO)

    override fun trashRequest(uris: List<String>): IntentSender? =
        mediaOperator.trashRequest(uris)

    override suspend fun thumbnail(uri: String, sizePx: Int): Bitmap? =
        thumbnailLoader.load(uri, sizePx)

    override suspend fun fullImage(uri: String, maxSizePx: Int): Bitmap? =
        fullImageLoader.load(uri, maxSizePx)

    override fun purgeRequest(uris: List<String>): IntentSender? = mediaOperator.purgeRequest(uris)

    override fun untrashRequest(uris: List<String>): IntentSender? = mediaOperator.untrashRequest(uris)

    override fun moveRequest(uris: List<String>): IntentSender? = mediaOperator.writeRequest(uris)

    override suspend fun moveToAlbum(uris: List<String>, targetRelativePath: String): Int =
        withContext(Dispatchers.IO) {
            mediaOperator.moveToAlbum(uris, targetRelativePath)
        }
}
