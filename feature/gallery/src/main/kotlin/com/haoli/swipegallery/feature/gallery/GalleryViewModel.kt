package com.haoli.swipegallery.feature.gallery

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haoli.swipegallery.core.data.MediaRepository
import com.haoli.swipegallery.core.data.settings.SettingsRepository
import com.haoli.swipegallery.core.model.AppSettings
import com.haoli.swipegallery.core.model.DateRange
import com.haoli.swipegallery.core.model.LibrarySnapshot
import com.haoli.swipegallery.core.model.MediaAlbum
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.core.model.MediaKind
import com.haoli.swipegallery.core.model.SortDirection
import com.haoli.swipegallery.core.model.SortField
import com.haoli.swipegallery.core.model.SortSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GalleryUiState(
    val kind: MediaKind = MediaKind.IMAGE,
    val sort: SortSpec = SortSpec(),
    /** null 表示「全部」，不做相册过滤。 */
    val albumId: Long? = null,
    /** 日期范围筛选。null 表示不限日期。 */
    val dateRange: DateRange? = null,
    /** 网格每行几列。由首屏双指缩放控制。 */
    val columns: Int = AppSettings.DEFAULT_GRID_COLUMNS,
    /**
     * 回收站里的项数与占用。
     *
     * 单独放在状态里是因为它最容易让人困惑：删除后如果界面上看不到
     * 「还有多少空间被占着」，用户会以为删除没生效。
     */
    val trashCount: Int = 0,
    val trashBytes: Long = 0L,
    val albums: List<MediaAlbum> = emptyList(),
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
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    /** 持有当前查询任务，新的查询会取消上一次。 */
    private var reloadJob: Job? = null

    init {
        // 只把默认设置读进来，不在这里发起查询 ——
        // 真正的首次加载由权限回调触发，没权限时查了也是空的
        viewModelScope.launch {
            val defaults = settingsRepository.settings.first()
            _state.update {
                it.copy(sort = defaults.defaultSort, columns = defaults.gridColumns)
            }
        }
    }

    /**
     * 把设置里的默认值同步到网格并重新查询。
     *
     * 用户从设置页返回时必须调用：改了默认排序后，只有这里会把它同步过来。
     * 网格列数也在这里同步 —— 它在设置页里同样可以改。
     */
    fun syncSettings() {
        viewModelScope.launch {
            val defaults = settingsRepository.settings.first()
            val changed = _state.value.sort != defaults.defaultSort
            _state.update {
                it.copy(
                    sort = defaults.defaultSort,
                    columns = defaults.gridColumns,
                    loading = it.loading || changed,
                )
            }
            reload()
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(permissionGranted = granted) }
        if (granted) reload()
    }

    fun setKind(kind: MediaKind) {
        if (_state.value.kind == kind) return
        // 相册是按类型划分的，切换类型时必须清掉已选相册，否则会查出空列表
        _state.update { it.copy(kind = kind, albumId = null, loading = true) }
        reload()
    }


    /**
     * 双指缩放调整网格列数。
     *
     * [delta] 为正表示放大（手指张开）→ 列数减少、格子变大。
     * 只改界面密度，不触发重新查询 —— 数据没变，重新查库纯属浪费。
     */
    fun zoomColumns(delta: Int) {
        if (delta == 0) return
        val current = _state.value.columns
        val next = AppSettings.sanitizeGridColumns(current + delta)
        if (next == current) return

        _state.update { it.copy(columns = next) }
        viewModelScope.launch { settingsRepository.setGridColumns(next) }
    }

    /**
     * 设置日期范围。传 null 表示不限日期。
     *
     * 与其他筛选项一样，改完要重新查询 —— 这里会取消上一次未完成的查询，
     * 快速连点时不会出现旧结果覆盖新结果。
     */
    fun setDateRange(range: DateRange?) {
        if (_state.value.dateRange == range) return
        _state.update { it.copy(dateRange = range, loading = true) }
        reload()
    }

    /** [albumId] 传 null 表示回到「全部」。 */
    fun selectAlbum(albumId: Long?) {
        if (_state.value.albumId == albumId) return
        _state.update { it.copy(albumId = albumId, loading = true) }
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
    /**
     * 重新查询。
     *
     * 会取消上一次未完成的查询，避免慢的旧结果覆盖快的新结果。
     */
    fun reload() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            val current = _state.value
            val items = repository
                .observeItems(current.kind, current.sort, current.albumId, current.dateRange)
                .first()
            val albums = repository.observeAlbums(current.kind).first()
            val snapshot = repository.observeSnapshot().first()

            // 回收站内容不随类型/相册/搜索变化，单独读一次
            val trash = try {
                repository.observeTrash().first()
            } catch (_: Exception) {
                emptyList()
            }

            _state.update { previous ->
                previous.copy(
                    items = items,
                    albums = albums,
                    snapshot = snapshot,
                    loading = false,
                    // 已选相册可能因为外部删除而消失，此时回到「全部」，
                    // 否则界面会停在一个查不出任何内容的筛选条件上
                    albumId = previous.albumId?.takeIf { id -> albums.any { it.id == id } },
                    trashCount = trash.size,
                    trashBytes = trash.sumOf { it.sizeBytes },
                )
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
