package com.haoli.swipegallery.core.data

import com.haoli.swipegallery.core.model.LibrarySnapshot
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.core.model.MediaKind
import com.haoli.swipegallery.core.model.SortSpec
import kotlinx.coroutines.flow.Flow

/**
 * 媒体数据的唯一出入口。
 *
 * UI 层只与它打交道，不直接接触 ContentResolver。
 * 具体实现见 [MediaStoreMediaRepository]。
 */
interface MediaRepository {

    /** 媒体库概览，统计页与首页使用。 */
    fun observeSnapshot(): Flow<LibrarySnapshot>

    /** 按类型与排序规则取媒体列表。滑卡队列由它生成。 */
    fun observeItems(kind: MediaKind, sort: SortSpec): Flow<List<MediaItem>>
}
