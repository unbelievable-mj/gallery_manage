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
    val items: List<MediaItem> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val loading: Boolean = true,
) {
    val hasSelection: Boolean get() = selectedIds.isNotEmpty()
    val allSelected: Boolean get() = items.isNotEmpty() && selectedIds.size == items.size
}

/**
 * 系统回收站页面的状态持有者。
 *
 * 这一页存在的意义是**让删除可被验证**：滑卡删除走的是 `createTrashRequest`，
 * 内容进的是系统回收站而不是被永久删除，但回收站里的文件不会出现在常规查询里，
 * 用户在相册中看不到就会以为被真删了。把 IS_TRASHED = 1 的内容列出来，
 * 用户才能确认东西还在、并且能取回。
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val items = repository.observeTrashed().first()
            val alive = items.mapTo(HashSet()) { it.id }
            _state.update { previous ->
                previous.copy(
                    items = items,
                    loading = false,
                    // 已选中的项可能已被别处取回或清理，剔除掉避免操作到不存在的目标
                    selectedIds = previous.selectedIds intersect alive,
                )
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

    /** 把选中项移出回收站，回到相册。 */
    fun restoreSelected(): IntentSender? =
        repository.untrashRequest(selectedUris() ?: return null)

    /** 永久删除选中项。跳过回收站，不可恢复。 */
    fun deleteSelectedForever(): IntentSender? =
        repository.deleteRequest(selectedUris() ?: return null)

    private fun selectedUris(): List<String>? {
        val snapshot = _state.value
        if (snapshot.selectedIds.isEmpty()) return null
        return snapshot.items.filter { it.id in snapshot.selectedIds }.map { it.uri }
    }

    suspend fun loadThumbnail(uri: String): Bitmap? = repository.thumbnail(uri, THUMBNAIL_PX)

    private companion object {
        const val THUMBNAIL_PX = 320
    }
}
