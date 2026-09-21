package com.haoli.swipegallery.feature.gallery

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haoli.swipegallery.core.data.MediaRepository
import com.haoli.swipegallery.core.model.LibrarySnapshot
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.core.model.MediaKind
import com.haoli.swipegallery.core.model.SortDirection
import com.haoli.swipegallery.core.model.SortField
import com.haoli.swipegallery.core.model.SortSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GalleryUiState(
    val kind: MediaKind = MediaKind.IMAGE,
    val sort: SortSpec = SortSpec(),
    val items: List<MediaItem> = emptyList(),
    val snapshot: LibrarySnapshot = LibrarySnapshot(0, 0, 0L, isStub = true),
    val loading: Boolean = true,
    val permissionGranted: Boolean = false,
)

/**
 * 网格页的状态持有者。
 *
 * 刻意不用 `flatMapLatest` 组合数据流：这里的数据只会在「切换类型」「改变排序」
 * 「外部触发刷新」三种时机变化，显式调用 [reload] 比搭一条响应式管道更好读，
 * 也更容易在滑卡删除后精确控制刷新时机。
 */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(permissionGranted = granted) }
        if (granted) reload()
    }

    fun setKind(kind: MediaKind) {
        if (_state.value.kind == kind) return
        _state.update { it.copy(kind = kind, loading = true) }
        reload()
    }

    fun setSortField(field: SortField) {
        if (_state.value.sort.field == field) return
        _state.update { it.copy(sort = it.sort.copy(field = field), loading = true) }
        reload()
    }

    fun toggleSortDirection() {
        val next = when (_state.value.sort.direction) {
            SortDirection.DESC -> SortDirection.ASC
            SortDirection.ASC -> SortDirection.DESC
        }
        _state.update { it.copy(sort = it.sort.copy(direction = next), loading = true) }
        reload()
    }

    /**
     * 重新查询媒体库。
     *
     * 滑卡模式退出后必须调用一次 —— 阶段二的删除可能已经真正落盘，
     * 列表需要与系统状态对齐。
     */
    fun reload() {
        viewModelScope.launch {
            val current = _state.value
            val items = repository.observeItems(current.kind, current.sort).first()
            val snapshot = repository.observeSnapshot().first()
            _state.update {
                it.copy(items = items, snapshot = snapshot, loading = false)
            }
        }
    }

    /** 供网格单元加载缩略图。图片与视频走同一条路径。 */
    suspend fun loadThumbnail(uri: String): Bitmap? = repository.thumbnail(uri, THUMBNAIL_PX)

    private companion object {
        /** 三列网格在 1080p 屏上约 360px/格，取 320 兼顾清晰度与内存。 */
        const val THUMBNAIL_PX = 320
    }
}
