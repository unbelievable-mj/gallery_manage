package com.haoli.swipegallery.feature.gallery

import android.content.IntentSender
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haoli.swipegallery.core.data.MediaRepository
import com.haoli.swipegallery.core.data.usage.UsageRepository
import com.haoli.swipegallery.core.model.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
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
    /** 历史累计：通过永久删除释放掉的空间。只增不减。 */
    val totalFreedBytes: Long = 0L,
) {
    val hasSelection: Boolean get() = selectedIds.isNotEmpty()
    val allSelected: Boolean get() = items.isNotEmpty() && selectedIds.size == items.size
    val isEmpty: Boolean get() = items.isEmpty()

    /**
     * 回收站内容占用的空间。
     *
     * 这个数字必须显眼：回收站里的内容**仍占着磁盘**，
     * 用户看不到它就会以为「删了但空间没变 = 删除没生效」。
     */
    val totalBytes: Long get() = items.sumOf { it.sizeBytes }

    /** 当前选中项占用的空间，让用户按下「永久删除」之前知道能腾出多少。 */
    val selectedBytes: Long get() = items.filter { it.id in selectedIds }.sumOf { it.sizeBytes }
}

/**
 * 回收站页面的状态持有者。
 *
 * **这里展示的就是系统回收站**（`IS_TRASHED = 1`），不是应用另存的一份记录。
 * 早先的版本在应用里维护了一套独立记录，结果是两套互不相干的回收站：
 * 在应用里删掉的东西在手机相册的回收站里看不到，反之亦然。
 * 现在两边读的是同一份数据，天然一致。
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val usageRepository: UsageRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    /** 本次待删内容的体积，等用户确认后累加到「累计释放」。 */
    private var pendingPurgeBytes: Long = 0L

    init {
        reload()
        viewModelScope.launch {
            usageRepository.freedBytes.collect { total ->
                _state.update { it.copy(totalFreedBytes = total) }
            }
        }
    }

    /**
     * 重新读取系统回收站。
     *
     * 不做持续订阅：回收站的变化由系统与我们自己的操作驱动，每次操作后主动读一次即可。
     * 查询在未实现回收站的设备上可能抛异常，这里兜住，让页面退化成空列表而不是崩掉。
     */
    fun reload() {
        viewModelScope.launch { readOnce() }
    }

    /** 读一次系统回收站并写入状态。 */
    private suspend fun readOnce() {
        val items = try {
            repository.observeTrash().first()
        } catch (_: Exception) {
            emptyList()
        }
        val alive = items.mapTo(HashSet()) { it.id }
        _state.update { previous ->
            previous.copy(
                items = items,
                loading = false,
                // 选中项可能已被别处取回或清理，剔除失效的
                selectedIds = previous.selectedIds intersect alive,
            )
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

    /** 从系统回收站取回。文件回到原相册。 */
    fun restoreSelected(): IntentSender? =
        repository.untrashRequest(selectedUris() ?: return null)

    /** 永久删除。**这是全应用唯一会真正释放空间的操作。** */
    fun purgeSelected(): IntentSender? {
        val uris = selectedUris() ?: return null
        // 先记下这次能释放多少，等系统对话框确认后再累加 ——
        // 用户取消的话不能算进「累计释放」
        pendingPurgeBytes = _state.value.selectedBytes
        return repository.purgeRequest(uris)
    }

    /**
     * 系统对话框返回后调用。
     *
     * [removed] 为 true 表示用户确认了永久删除。
     *
     * 这时必须**乐观地**先把选中项从列表里摘掉：系统的删除是异步落库的，
     * 授权框返回的那一瞬间 MediaProvider 往往还没写完，立刻重查拿到的仍是旧结果 ——
     * 这正是「清理完必须退出重进才刷新」的原因。
     *
     * 摘掉之后再延迟重查几次，与系统真实状态对齐（避免乐观更新与真实状态长期不一致）。
     */
    fun onActionFinished(removed: Boolean) {
        val selected = _state.value.selectedIds
        val freedNow = if (removed) pendingPurgeBytes else 0L
        pendingPurgeBytes = 0L

        _state.update { snapshot ->
            snapshot.copy(
                items = if (removed) {
                    snapshot.items.filterNot { it.id in selected }
                } else {
                    snapshot.items
                },
                selectedIds = emptySet(),
            )
        }

        if (freedNow > 0L) {
            viewModelScope.launch { usageRepository.addFreed(freedNow) }
        }

        viewModelScope.launch {
            repeat(RECONCILE_ATTEMPTS) { attempt ->
                if (attempt > 0) delay(RECONCILE_INTERVAL_MS)
                readOnce()
            }
        }
    }

    private fun selectedUris(): List<String>? {
        val snapshot = _state.value
        if (snapshot.selectedIds.isEmpty()) return null
        return snapshot.items.filter { it.id in snapshot.selectedIds }.map { it.uri }
    }

    suspend fun loadThumbnail(uri: String): Bitmap? = repository.thumbnail(uri, THUMBNAIL_PX)

    private companion object {
        const val THUMBNAIL_PX = 320

        /** 与系统状态对齐的重试次数与间隔：删除落库需要一点时间，一次查询常常赶不上。 */
        const val RECONCILE_ATTEMPTS = 4
        const val RECONCILE_INTERVAL_MS = 600L
    }
}
