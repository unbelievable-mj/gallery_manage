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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrashUiState(
    val items: List<MediaItem> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val loading: Boolean = true,
) {
    val hasSelection: Boolean get() = selectedIds.isNotEmpty()
    val allSelected: Boolean get() = items.isNotEmpty() && selectedIds.size == items.size
}

/**
 * 回收站页面的状态持有者。
 *
 * 这里管理的是**应用自己的回收站**，不是系统回收站。
 * 滑卡删除只写一条记录，文件原封不动 —— 所以「恢复」是瞬时的，
 * 而「彻底删除」是这个应用里唯一会真正销毁文件的路径。
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    /** 已发起「彻底删除」的记录，等系统对话框返回后决定去留。 */
    private var pendingPurgeIds: List<Long> = emptyList()

    init {
        viewModelScope.launch {
            repository.observeTrash().collect { items ->
                val alive = items.mapTo(HashSet()) { it.id }
                _state.update { previous ->
                    previous.copy(
                        items = items,
                        loading = false,
                        // 记录可能已被别处恢复或清理，剔除失效的选中项
                        selectedIds = previous.selectedIds intersect alive,
                    )
                }
            }
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
        _state.update { it.copy(selectedIds = it.items.mapTo(HashSet()) { item -> item.id }) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedIds = emptySet()) }
    }

    /** 取回。只删掉记录，文件从未被动过，因此瞬时完成。 */
    fun restoreSelected() {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.restoreFromTrash(ids)
            _state.update { it.copy(selectedIds = emptySet()) }
        }
    }

    /**
     * 彻底删除选中项。这是全应用唯一会真正销毁文件的地方。
     *
     * 返回的 IntentSender 交给 `StartIntentSenderForResult` 启动，
     * 系统会弹一次确认框 —— 这正是我们想要的：不可逆的操作必须有明确确认。
     */
    fun purgeSelected(): IntentSender? {
        val snapshot = _state.value
        if (snapshot.selectedIds.isEmpty()) return null

        val uris = snapshot.items
            .filter { it.id in snapshot.selectedIds }
            .map { it.uri }

        pendingPurgeIds = snapshot.selectedIds.toList()
        return repository.deleteRequest(uris)
    }

    /**
     * 用户在系统对话框里确认了删除。
     *
     * 只在确认时清记录：如果用户取消，内容仍留在回收站里，
     * 不会出现「点了取消但东西也没了」。
     */
    fun onPurgeConfirmed() {
        val ids = pendingPurgeIds
        pendingPurgeIds = emptyList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.restoreFromTrash(ids)
            _state.update { it.copy(selectedIds = emptySet()) }
        }
    }

    fun onPurgeDismissed() {
        pendingPurgeIds = emptyList()
    }

    suspend fun loadThumbnail(uri: String): Bitmap? = repository.thumbnail(uri, THUMBNAIL_PX)

    private companion object {
        const val THUMBNAIL_PX = 320
    }
}
