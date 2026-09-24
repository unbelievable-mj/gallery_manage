package com.haoli.swipegallery.feature.gallery

import android.content.IntentSender
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haoli.swipegallery.core.data.MediaRepository
import com.haoli.swipegallery.core.model.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrashUiState(
    /** 应用自己的回收站：滑卡删除的内容先到这里，文件未被动过。 */
    val appItems: List<MediaItem> = emptyList(),
    /**
     * 系统回收站中的内容。
     *
     * 设备/存储卷不支持回收站时恒为空 —— 界面据此隐藏这一区块。
     */
    val systemItems: List<MediaItem> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val loading: Boolean = true,
) {
    val hasSelection: Boolean get() = selectedIds.isNotEmpty()
    val allSelected: Boolean get() = appItems.isNotEmpty() && selectedIds.size == appItems.size
    val isEmpty: Boolean get() = appItems.isEmpty() && systemItems.isEmpty()
}

/**
 * 回收站页面的状态持有者。
 *
 * 两个来源，语义不同：
 *  - **应用回收站**：滑卡删除只写一条记录，文件原封不动，所以「恢复」是瞬时的
 *  - **系统回收站**：用户在应用里点「删除」后，内容会尽量交给系统回收站
 *    （设备支持时）。这里把系统回收站里的内容也列出来并提供取回，
 *    避免出现「删了之后在手机图库里找不到」的困惑。
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    /** 已发起删除的记录，等系统对话框返回后决定去留。 */
    private var pendingPurgeIds: List<Long> = emptyList()

    init {
        viewModelScope.launch {
            repository.observeTrash().collect { items ->
                val alive = items.mapTo(HashSet()) { it.id }
                _state.update { previous ->
                    previous.copy(
                        appItems = items,
                        loading = false,
                        // 记录可能已被别处恢复或清理，剔除失效的选中项
                        selectedIds = previous.selectedIds intersect alive,
                    )
                }
            }
        }
        reloadSystemTrash()
    }

    /**
     * 重新读取系统回收站。
     *
     * 不用持续订阅：系统回收站的变化不由我们驱动，只在页面进入与操作后各读一次。
     * 查询本身在部分 ROM 上可能抛异常（未实现 IS_TRASHED），这里兜住，
     * 让页面退化成「没有系统回收站」而不是崩掉。
     */
    fun reloadSystemTrash() {
        viewModelScope.launch {
            val items = try {
                repository.observeSystemTrash().first()
            } catch (_: Exception) {
                emptyList()
            }
            _state.update { it.copy(systemItems = items, loading = false) }
        }
    }

    fun toggleSelection(id: Long) {
        _state.update { snapshot ->
            val next = if (id in snapshot.selectedIds) {
                snapshot.selectedIds - id
            } else {
                snapshot.selectedIds + id
            }
            snapshot.copy(selectedIds = next)
        }
    }

    fun selectAll() {
        _state.update { it.copy(selectedIds = it.appItems.mapTo(HashSet()) { item -> item.id }) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedIds = emptySet()) }
    }

    /** 从应用回收站取回。只删掉记录，文件从未被动过，因此瞬时完成。 */
    fun restoreSelected() {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.restoreFromTrash(ids)
            _state.update { it.copy(selectedIds = emptySet()) }
        }
    }

    /**
     * 删除选中项。
     *
     * 返回的 IntentSender 交给 `StartIntentSenderForResult` 启动。
     * 优先移入系统回收站，设备不支持时退回永久删除 —— 无论哪种都符合用户意图。
     */
    fun purgeSelected(): IntentSender? {
        val snapshot = _state.value
        if (snapshot.selectedIds.isEmpty()) return null

        val uris = snapshot.appItems
            .filter { it.id in snapshot.selectedIds }
            .map { it.uri }

        pendingPurgeIds = snapshot.selectedIds.toList()
        return repository.purgeRequest(uris)
    }

    /** 用户在系统对话框里确认了删除。 */
    fun onPurgeConfirmed() {
        val ids = pendingPurgeIds
        pendingPurgeIds = emptyList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.restoreFromTrash(ids)
            _state.update { it.copy(selectedIds = emptySet()) }
            reloadSystemTrash()
        }
    }

    /**
     * 用户在系统对话框里取消了。
     *
     * 记录保持不动：内容继续留在回收站里，不能出现「点了取消东西却没了」。
     */
    fun onPurgeDismissed() {
        pendingPurgeIds = emptyList()
    }

    /** 从系统回收站取回单项。 */
    fun restoreSystemItem(item: MediaItem): IntentSender? = repository.untrashRequest(listOf(item.uri))

    fun onSystemRestoreFinished() {
        reloadSystemTrash()
    }

    suspend fun loadThumbnail(uri: String): Bitmap? = repository.thumbnail(uri, THUMBNAIL_PX)

    private companion object {
        const val THUMBNAIL_PX = 320
    }
}
