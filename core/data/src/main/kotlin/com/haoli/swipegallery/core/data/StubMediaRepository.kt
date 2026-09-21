package com.haoli.swipegallery.core.data

import com.haoli.swipegallery.core.model.LibrarySnapshot
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.core.model.MediaKind
import com.haoli.swipegallery.core.model.SortSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * M0 骨架阶段的占位实现。
 *
 * 它存在的意义是让依赖注入图和多模块依赖链在 M0 就被真实验证一遍：
 * 只要这个类能被注入到 ViewModel 里，说明 Hilt + KSP + AGP 9 内置 Kotlin
 * 这条链路是通的。
 *
 * M1 会把它替换为真正查询 MediaStore 的 `MediaStoreMediaRepository`，
 * 届时 [LibrarySnapshot.isStub] 将恒为 false。
 */
@Singleton
class StubMediaRepository @Inject constructor() : MediaRepository {

    override fun observeSnapshot(): Flow<LibrarySnapshot> = flowOf(
        LibrarySnapshot(
            imageCount = 0,
            videoCount = 0,
            totalSizeBytes = 0L,
            isStub = true,
        ),
    )

    override fun observeItems(kind: MediaKind, sort: SortSpec): Flow<List<MediaItem>> =
        flowOf(emptyList())
}
