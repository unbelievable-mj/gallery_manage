package com.haoli.swipegallery.core.data

import android.content.IntentSender
import android.graphics.Bitmap
import com.haoli.swipegallery.core.data.media.FullImageLoader
import com.haoli.swipegallery.core.data.media.MediaOperator
import com.haoli.swipegallery.core.data.media.MediaStoreDataSource
import com.haoli.swipegallery.core.data.media.ThumbnailLoader
import com.haoli.swipegallery.core.model.LibrarySnapshot
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.core.model.MediaKind
import com.haoli.swipegallery.core.model.SortSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * 基于 MediaStore 的实现。
 *
 * 只是把三件事编排到一起：查询（[MediaStoreDataSource]）、
 * 解码（[ThumbnailLoader] / [FullImageLoader]）、写操作（[MediaOperator]）。
 * 自身不含业务逻辑，也不缓存列表 —— 缓存策略留给上层决定。
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

    override fun observeItems(kind: MediaKind, sort: SortSpec): Flow<List<MediaItem>> = flow {
        emit(dataSource.items(kind, sort))
    }.flowOn(Dispatchers.IO)

    override suspend fun thumbnail(uri: String, sizePx: Int): Bitmap? =
        thumbnailLoader.load(uri, sizePx)

    override suspend fun fullImage(uri: String, maxSizePx: Int): Bitmap? =
        fullImageLoader.load(uri, maxSizePx)

    override fun trashRequest(uris: List<String>): IntentSender? = mediaOperator.trashRequest(uris)

    override fun untrashRequest(uris: List<String>): IntentSender? = mediaOperator.untrashRequest(uris)

    override fun deleteRequest(uris: List<String>): IntentSender? = mediaOperator.deleteRequest(uris)
}
