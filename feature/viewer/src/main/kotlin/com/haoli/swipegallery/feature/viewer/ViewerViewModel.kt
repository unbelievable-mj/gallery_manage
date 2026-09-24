package com.haoli.swipegallery.feature.viewer

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haoli.swipegallery.core.data.MediaRepository
import com.haoli.swipegallery.core.data.settings.SettingsRepository
import com.haoli.swipegallery.core.model.AppSettings
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.core.model.TriageAction
import com.haoli.swipegallery.core.model.TriageProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 用户在卡片上滑动的方向。 */
enum class SwipeDirection { UP, DOWN }

data class ViewerUiState(
    /**
     * 是否已经注入过队列。
     *
     * 没有这个标志的话，首帧 items 为空会被判成「处理完了」，
     * 用户点开图片会先闪一下结束页再出现照片。
     */
    val started: Boolean = false,
    /** 当前队列。已决策的项会被移出，因此它的长度就是「剩余」。 */
    val items: List<MediaItem> = emptyList(),
    val currentIndex: Int = 0,
    val progress: TriageProgress = TriageProgress(0, 0, 0, 0),
    val canUndo: Boolean = false,
    /** 最近一次操作提示，用于顶部文案。 */
    val lastActionLabel: String? = null,
) {
    val current: MediaItem? get() = items.getOrNull(currentIndex)
    val isFinished: Boolean get() = started && items.isEmpty()
}

/**
 * 滑卡处理的状态持有者，实现两阶段提交的「阶段一」。
 *
 * 关键点：上滑删除**不立即触碰系统**。文件只是被移出队列并记进 [pendingTrash]，
 * 于是撤销变成一次纯内存操作，瞬时且无损。真正落盘发生在 [flushToSystemTrash]，
 * 由界面在退出查看器或撤销窗口过期时触发。
 *
 * 直接删盘的话，「滑走即可撤回」这条需求根本无法实现 —— 系统删除是不可逆的。
 */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val repository: MediaRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    /** 阶段一产物：已决策删除、但尚未提交给系统的文件。 */
    private val pendingTrash = mutableListOf<MediaItem>()

    private val undoStack = ArrayDeque<TriageAction>()

    private var preloadJob: Job? = null

    /**
     * 预加载张数。默认 3（用户明确要求「默认缓存三张」），
     * 启动时从设置读取覆盖。
     */
    private var preloadCount: Int = AppSettings.DEFAULT_PRELOAD_COUNT

    private var queueTotal = 0
    private var deletedCount = 0
    private var keptCount = 0

    // 必须放在 preloadCount 声明之后：Kotlin 的初始化器按书写顺序执行，
    // 写在前面的话，属性初始化会把这里读到的值覆盖回默认值。
    init {
        viewModelScope.launch {
            preloadCount = settingsRepository.settings.first().preloadCount
        }
    }

    /** 打开查看器时注入队列与起始位置。 */
    fun start(items: List<MediaItem>, startIndex: Int) {
        pendingTrash.clear()
        undoStack.clear()
        queueTotal = items.size
        deletedCount = 0
        keptCount = 0

        val start = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        _state.value = ViewerUiState(
            started = true,
            items = items,
            currentIndex = start,
            progress = TriageProgress.of(items.size, 0, 0),
        )
        preloadAround(start)
    }

    fun onPageChanged(index: Int) {
        if (_state.value.currentIndex == index) return
        _state.update { it.copy(currentIndex = index) }
        preloadAround(index)
    }

    fun applySwipe(direction: SwipeDirection) {
        when (direction) {
            SwipeDirection.UP -> deleteCurrent()
            SwipeDirection.DOWN -> keepCurrent()
        }
    }

    private fun deleteCurrent() {
        val snapshot = _state.value
        val item = snapshot.current ?: return
        val index = snapshot.currentIndex

        pendingTrash += item
        undoStack.addLast(TriageAction.Deleted(item, index, System.currentTimeMillis()))
        deletedCount++

        _state.value = snapshot.afterRemoval(
            index = index,
            deleted = deletedCount,
            kept = keptCount,
            label = "已删除「${item.displayName}」",
        )
        // 当前项变了，重新铺预加载
        preloadAround(_state.value.currentIndex)
    }

    private fun keepCurrent() {
        val snapshot = _state.value
        val item = snapshot.current ?: return
        val index = snapshot.currentIndex

        undoStack.addLast(TriageAction.Kept(item, index, System.currentTimeMillis()))
        keptCount++

        _state.value = snapshot.afterRemoval(
            index = index,
            deleted = deletedCount,
            kept = keptCount,
            label = "已保留「${item.displayName}」",
        )
        preloadAround(_state.value.currentIndex)
    }

    /**
     * 撤销最近一次决策。
     *
     * 若撤销的是删除，同时把它从 [pendingTrash] 里摘掉 —— 否则退出时它仍会被提交，
     * 用户会遇到「明明撤销了，照片还是没了」。
     */
    fun undo() {
        val action = undoStack.removeLastOrNull() ?: return

        when (action) {
            is TriageAction.Deleted -> {
                pendingTrash.removeAll { it.id == action.item.id }
                deletedCount = (deletedCount - 1).coerceAtLeast(0)
            }

            is TriageAction.Kept -> {
                keptCount = (keptCount - 1).coerceAtLeast(0)
            }
        }

        val restored = _state.value.items.toMutableList().apply {
            add(action.originalIndex.coerceIn(0, size), action.item)
        }

        _state.update {
            it.copy(
                items = restored,
                currentIndex = action.originalIndex.coerceIn(0, (restored.size - 1).coerceAtLeast(0)),
                progress = TriageProgress.of(queueTotal, deletedCount, keptCount),
                canUndo = undoStack.isNotEmpty(),
                lastActionLabel = "已撤销「${action.item.displayName}」",
            )
        }
        preloadAround(_state.value.currentIndex)
    }

    fun clearActionLabel() {
        _state.update { it.copy(lastActionLabel = null) }
    }

    /** 查看器用全尺寸图（已降采样并纠正 EXIF 方向）。 */
    suspend fun loadFullImage(uri: String): Bitmap? = repository.fullImage(uri, FULL_IMAGE_PX)

    /**
     * 阶段二：把待删队列提交到**应用自己的回收站**。
     *
     * 只写一条记录，文件原封不动。因此这里没有系统对话框、不会被打断，
     * 更重要的是不会因为 ROM 对系统回收站的实现差异而丢数据。
     *
     * 真正的销毁由用户在回收站页面明确点「彻底删除」时才发生。
     */
    suspend fun commitToTrash() {
        if (pendingTrash.isEmpty()) return
        val targets = pendingTrash.toList()
        pendingTrash.clear()
        repository.addToTrash(targets)
    }

    private fun ViewerUiState.afterRemoval(
        index: Int,
        deleted: Int,
        kept: Int,
        label: String,
    ): ViewerUiState {
        val remaining = items.toMutableList().apply { removeAt(index) }
        return copy(
            items = remaining,
            currentIndex = index.coerceAtMost((remaining.size - 1).coerceAtLeast(0)),
            progress = TriageProgress.of(queueTotal, deleted, kept),
            canUndo = true,
            lastActionLabel = label,
        )
    }

    /**
     * 预加载当前项前后若干张的全尺寸图。
     *
     * 目的是滑走之后下一张已经解码完毕，不出现空白帧。
     *
     * 用单个协程顺序解码而非并发：几张大图同时解码会瞬间顶高内存峰值，
     * 低端机上容易 OOM；而且滑得快时上一轮预加载已经没有意义，
     * 直接 cancel 掉比让它跑完更省资源。
     */
    private fun preloadAround(index: Int) {
        preloadJob?.cancel()

        val items = _state.value.items
        if (items.isEmpty()) return

        val targets = buildList {
            for (offset in 1..preloadCount) {
                items.getOrNull(index + offset)?.let { add(it) }
            }
            // 往回也留一张，方便用户反悔时往回翻
            items.getOrNull(index - 1)?.let { add(it) }
        }
        if (targets.isEmpty()) return

        preloadJob = viewModelScope.launch {
            for (target in targets) {
                ensureActive()
                repository.fullImage(target.uri, FULL_IMAGE_PX)
            }
        }
    }

    private companion object {
        /** 2048 足以覆盖 1440p 屏的全屏显示，又不至于让大图撑爆内存。 */
        const val FULL_IMAGE_PX = 2048
    }
}
