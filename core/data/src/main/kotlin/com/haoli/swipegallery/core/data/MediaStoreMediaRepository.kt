package com.haoli.swipegallery.core.data

import android.content.IntentSender
import android.graphics.Bitmap
import com.haoli.swipegallery.core.data.media.FullImageLoader
import com.haoli.swipegallery.core.data.media.MediaOperator
import com.haoli.swipegallery.core.data.media.MediaStoreDataSource
import com.haoli.swipegallery.core.data.media.ThumbnailLoader
import com.haoli.swipegallery.core.data.trash.TrashDao
import com.haoli.swipegallery.core.data.trash.toMediaItem
import com.haoli.swipegallery.core.data.trash.toTrashEntity
import com.haoli.swipegallery.core.model.LibrarySnapshot
import com.haoli.swipegallery.core.model.MediaAlbum
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.core.model.MediaKind
import com.haoli.swipegallery.core.model.SortSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 基于 MediaStore 的实现。
 *
 * 三件事：查询（[MediaStoreDataSource]）、解码（[ThumbnailLoader] / [FullImageLoader]）、
 * 写操作（[MediaOperator]），再加上应用自己的回收站（[TrashDao]）。
 */
@Singleton
class MediaStoreMediaRepository @Inject constructor(
    private val dataSource: MediaStoreDataSource,
    private val thumbnailLoader: ThumbnailLoader,
    private val fullImageLoader: FullImageLoader,
    private val mediaOperator: MediaOperator,
    private val trashDao: TrashDao,
) : MediaRepository {

    override fun observeSnapshot(): Flow<LibrarySnapshot> = flow {
        emit(dataSource.snapshot())
    }.flowOn(Dispatchers.IO)

    override fun observeItems(
        kind: MediaKind,
        sort: SortSpec,
        albumId: Long?,
    ): Flow<List<MediaItem>> = flow {
        // 回收站里的项仍然存在于 MediaStore，必须在这里剔除，
        // 否则用户「删掉」的内容会立刻重新出现在网格里
        val hidden = trashDao.allMediaIds().toHashSet()
        emit(dataSource.items(kind, sort, albumId).filterNot { it.id in hidden })
    }.flowOn(Dispatchers.IO)

    override fun observeAlbums(kind: MediaKind): Flow<List<MediaAlbum>> = flow {
        emit(dataSource.albums(kind))
    }.flowOn(Dispatchers.IO)

    override fun observeTrash(): Flow<List<MediaItem>> =
        trashDao.observeAll().map { entries -> entries.map { it.toMediaItem() } }

    override suspend fun addToTrash(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            trashDao.insertAll(items.map { it.toTrashEntity(now) })
        }
    }

    override suspend fun restoreFromTrash(mediaIds: List<Long>) {
        if (mediaIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            trashDao.deleteByMediaIds(mediaIds)
        }
    }

    override suspend fun thumbnail(uri: String, sizePx: Int): Bitmap? =
        thumbnailLoader.load(uri, sizePx)

    override suspend fun fullImage(uri: String, maxSizePx: Int): Bitmap? =
        fullImageLoader.load(uri, maxSizePx)

    override fun deleteRequest(uris: List<String>): IntentSender? = mediaOperator.deleteRequest(uris)
}
