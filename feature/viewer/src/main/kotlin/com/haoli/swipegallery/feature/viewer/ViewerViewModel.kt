package com.haoli.swipegallery.feature.viewer

import android.content.IntentSender
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haoli.swipegallery.core.data.MediaRepository
import com.haoli.swipegallery.core.data.settings.SettingsRepository
import com.haoli.swipegallery.core.model.AppSettings
import com.haoli.swipegallery.core.model.KeepTarget
import com.haoli.swipegallery.core.model.MoveTarget
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

/**
 * 一次滑卡会话的结果摘要。
 *
 * 退出查看器时用它给用户一个交代 —— 否则「删了几张、移了几张」
 * 全靠用户自己回忆，尤其是移动被系统授权框拦下时完全无感。
 */
data class TriageSummary(
    val deleted: Int = 0,
    val moved: Int = 0,
    val kept: Int = 0,
    val moveTargetName: String? = null,
) {
    val hasChanges: Boolean get() = deleted > 0 || moved > 0
}

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
    /** 已标记「移动到目标相册」的项数。 */
    val movedCount: Int = 0,
    /**
     * 下滑落点的相册名。null 表示「保留在原相册」。
     * 界面用它把提示文案改成「下滑移到「X」」—— 否则用户看到的
     * 提示与实际行为对不上。
     */
    val moveTargetName: String? = null,
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
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    /** 阶段一产物之一：已决策删除、但尚未提交的文件。 */
    private val pendingTrash = mutableListOf<MediaItem>()

    /** 阶段一产物之二：已决策移动到目标相册、但尚未提交的文件。 */
    private val pendingMoves = mutableListOf<MediaItem>()

    private val undoStack = ArrayDeque<TriageAction>()

    private var preloadJob: Job? = null

    /**
     * 预加载张数。默认 3（用户明确要求「默认缓存三张」），
     * 启动时从设置读取覆盖。
     */
    private var preloadCount: Int = AppSettings.DEFAULT_PRELOAD_COUNT

    /** 下滑的落点。null 表示「保留在原相册」，此时下滑不改动文件。 */
    private var moveTarget: MoveTarget? = null

    private var queueTotal = 0
    private var deletedCount = 0
    private var keptCount = 0
    private var movedCount = 0

    // 必须放在被赋值的属性声明之后：Kotlin 的初始化器按书写顺序执行，
    // 写在前面的话，属性初始化会把这里读到的值覆盖回默认值。
    //
    // 用持续订阅而不是读一次：用户可能在设置页改了预加载张数或目标相册，
    // 读一次的话 ViewModel 存活期间会一直用旧值。
    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { loaded ->
                preloadCount = loaded.preloadCount
                moveTarget = loaded.moveTarget
                _state.update { it.copy(moveTargetName = loaded.moveTarget?.albumName) }
            }
        }
    }

    /** 打开查看器时注入队列与起始位置。 */
    fun start(items: List<MediaItem>, startIndex: Int) {
        pendingTrash.clear()
        pendingMoves.clear()
        undoStack.clear()
        queueTotal = items.size
        deletedCount = 0
        keptCount = 0
        movedCount = 0

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
            moved = movedCount,
            label = "已删除「${item.displayName}」",
        )
        // 当前项变了，重新铺预加载
        preloadAround(_state.value.currentIndex)
    }

    private fun keepCurrent() {
        val snapshot = _state.value
        val item = snapshot.current ?: return
        val index = snapshot.currentIndex

        val target = moveTarget

        undoStack.addLast(
            TriageAction.Kept(
                item = item,
                originalIndex = index,
                timestampMillis = System.currentTimeMillis(),
                // 记下真实的落点，撤销逻辑与后续统计都要靠它区分「原地保留」与「归档」
                target = if (target != null) {
                    KeepTarget.SPECIFIC_ALBUM
                } else {
                    KeepTarget.ORIGINAL_ALBUM
                },
            )
        )
        keptCount++
        val label = if (target != null) {
            // 只记进待移动队列，退出查看器时统一申请写权限并执行 ——
            // 每滑一次弹一次系统授权框会彻底打断节奏
            pendingMoves += item
            movedCount++
            "已标记移到「${target.albumName}」"
        } else {
            // 没有目标相册时下滑仍是「保留」：文件零改动
            "已保留「${item.displayName}」"
        }

        _state.value = snapshot.afterRemoval(
            index = index,
            deleted = deletedCount,
            kept = keptCount,
            moved = movedCount,
            label = label,
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
                // 若这项已被标记移动，一并从待移动队列摘掉 ——
                // 否则撤销之后它仍会在退出时被移走
                if (pendingMoves.removeAll { it.id == action.item.id }) {
                    movedCount = (movedCount - 1).coerceAtLeast(0)
                }
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
                movedCount = movedCount,
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
     * 阶段二：构造「移入系统回收站」请求。
     *
     * 滑卡时只记账、不碰系统 —— 每滑一次弹一次授权框会彻底打断节奏；
     * 会话结束时整批提交一次。
     *
     * 之所以进系统回收站而不是永久删除：这样内容在手机相册的回收站里也能看到、
     * 也能取回。应用的回收站就是系统回收站的一个视图，两边天然一致。
     */
    fun trashRequest(): IntentSender? {
        if (pendingTrash.isEmpty()) return null
        val targets = pendingTrash.map { it.uri }
        pendingTrash.clear()
        return repository.trashRequest(targets)
    }

    /** 当前会话的结果摘要。 */
    fun summary(): TriageSummary = TriageSummary(
        deleted = deletedCount,
        // keptCount 把「原地保留」与「移动」都算进去了，这里要减掉
        kept = (keptCount - movedCount).coerceAtLeast(0),
        moved = movedCount,
        moveTargetName = moveTarget?.albumName,
    )

    /**
     * 用户在授权框里拒绝时调用：文件留在原处，只清掉待移动队列。
     *
     * 不清的话队列会一直留着，下次退出时会再次弹出授权框 ——
     * 用户已经拒绝过一次了，不该再问第二遍。
     */
    fun discardMoves() {
        pendingMoves.clear()
    }

    /**
     * 构造「移动到目标相册」的写入授权请求。
     *
     * 返回 null 表示没有待移动项（或未配置目标相册），调用方应跳过授权直接结束。
     * 移动需要改写文件的 RELATIVE_PATH，必须先拿到系统授予的写权限。
     */
    fun moveRequest(): IntentSender? {
        val target = moveTarget ?: return null
        if (pendingMoves.isEmpty() || target.relativePath.isBlank()) return null
        return repository.moveRequest(pendingMoves.map { it.uri })
    }

    /**
     * 执行移动。**必须在 [moveRequest] 拿到用户同意后调用。**
     *
     * 返回成功条数。单条失败不影响其余项 —— 移动本质是改目录归属，
     * 失败的文件原封不动留在原地，不会丢。
     */
    suspend fun performMoves(): Int {
        val target = moveTarget ?: return 0
        if (pendingMoves.isEmpty()) return 0

        val targets = pendingMoves.toList()
        pendingMoves.clear()
        return repository.moveToAlbum(targets.map { it.uri }, target.relativePath)
    }

    private fun ViewerUiState.afterRemoval(
        index: Int,
        deleted: Int,
        kept: Int,
        moved: Int,
        label: String,
    ): ViewerUiState {
        val remaining = items.toMutableList().apply { removeAt(index) }
        return copy(
            items = remaining,
            currentIndex = index.coerceAtMost((remaining.size - 1).coerceAtLeast(0)),
            progress = TriageProgress.of(queueTotal, deleted, kept),
            canUndo = true,
            lastActionLabel = label,
            movedCount = moved,
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
