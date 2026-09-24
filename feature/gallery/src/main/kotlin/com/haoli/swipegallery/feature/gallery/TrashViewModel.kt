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

    /**
     * 待清理内容占用的空间。
     *
     * 这是这一页最该显眼的数字：回收站里的内容**仍在磁盘上**，
     * 用户清空后如果看不到占用下降，就会怀疑删除根本没生效。
     */
    val totalBytes: Long get() = appItems.sumOf { it.sizeBytes }

    /** 当前选中项占用的空间，让用户在按下「删除」之前知道能腾出多少。 */
    val selectedBytes: Long
        get() = appItems.filter { it.id in selectedIds }.sumOf { it.sizeBytes }

    val systemBytes: Long get() = systemItems.sumOf { it.sizeBytes }
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

    /** 本次删除的目标是系统回收站里的内容，而非应用回收站。 */
    private var purgingSystem = false

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
        if (purgingSystem) {
            purgingSystem = false
            reloadSystemTrash()
            return
        }

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
        purgingSystem = false
        pendingPurgeIds = emptyList()
    }

    /**
     * 永久删除系统回收站里的全部内容。
     *
     * 需要这个入口的原因：应用回收站里的内容被删除后，如果走的是「移入系统回收站」，
     * 空间不会释放；而用户往往找不到手机自带的回收站入口，那部分空间就卡住了。
     */
    fun purgeSystemTrash(): IntentSender? {
        val targets = _state.value.systemItems
        if (targets.isEmpty()) return null

        pendingPurgeIds = emptyList()
        purgingSystem = true
        return repository.purgeRequest(targets.map { it.uri })
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
