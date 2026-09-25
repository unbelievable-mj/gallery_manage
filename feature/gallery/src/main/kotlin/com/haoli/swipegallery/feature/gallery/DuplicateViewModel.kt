package com.haoli.swipegallery.feature.gallery

import android.content.IntentSender
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haoli.swipegallery.core.data.MediaRepository
import com.haoli.swipegallery.core.data.settings.SettingsRepository
import com.haoli.swipegallery.core.model.DuplicateScan
import com.haoli.swipegallery.core.model.MediaKind
import com.haoli.swipegallery.core.model.SortSpec
import com.haoli.swipegallery.core.model.groupSuspectedDuplicates
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DuplicateUiState(
    val scan: DuplicateScan = DuplicateScan(),
    /** 用 uri 而不是 id 标记选中：查重结果里同一个 id 只会出现一次，但 uri 更直观。 */
    val selectedUris: Set<String> = emptySet(),
    val loading: Boolean = true,
    val kind: MediaKind = MediaKind.IMAGE,
    /** 当前扫描范围。albumId 为 null 表示全部相册。 */
    val albumId: Long? = null,
    val albumName: String? = null,
) {
    val hasSelection: Boolean get() = selectedUris.isNotEmpty()

    val selectedBytes: Long
        get() = scan.groups
            .flatMap { it.items }
            .filter { it.uri in selectedUris }
            .sumOf { it.sizeBytes }
}

/**
 * 疑似重复文件的筛选。
 *
 * **判据只有文件大小接近，不做内容比对**，所以结果一定是「疑似」。
 * 连拍、同场景不同曝光都可能被归到一组 —— 界面必须让用户看缩略图自己判断，
 * 因此这里**刻意不提供「一键全部删除」**，只提供「每组保留最小的那份」这个
 * 相对保守的批量选择。
 */
@HiltViewModel
class DuplicateViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DuplicateUiState())
    val state: StateFlow<DuplicateUiState> = _state.asStateFlow()

    /**
     * 在当前类型与相册范围内扫描。
     *
     * 范围由调用方传入而不是自己决定：用户是在图库里选好类型和相册之后
     * 才点进查重的，这个上下文必须延续过来。
     */
    fun scan(kind: MediaKind, albumId: Long?, albumName: String?) {
        _state.update {
            it.copy(
                loading = true,
                kind = kind,
                albumId = albumId,
                albumName = albumName,
                selectedUris = emptySet(),
            )
        }

        viewModelScope.launch {
            val threshold = settingsRepository.settings.first().duplicateThresholdBytes
            val items = try {
                // 用默认排序即可：分组内部会按大小重排，排序方式不影响结果
                repository.observeItems(kind, SortSpec(), albumId)
                    .first()
            } catch (_: Exception) {
                emptyList()
            }

            _state.update {
                it.copy(
                    scan = DuplicateScan(
                        groups = items.groupSuspectedDuplicates(threshold),
                        scannedCount = items.size,
                        thresholdBytes = threshold,
                    ),
                    loading = false,
                )
            }
        }
    }

    fun toggle(uri: String) {
        _state.update { snapshot ->
            val next = if (uri in snapshot.selectedUris) {
                snapshot.selectedUris - uri
            } else {
                snapshot.selectedUris + uri
            }
            snapshot.copy(selectedUris = next)
        }
    }

    /**
     * 每组保留体积最小的那份，其余全部选中。
     *
     * 这是最保守的批量策略：同组里保留一份，不区分内容差异。
     * 之所以不做「全部选中」，是因为判据只有大小 —— 一组里很可能全是连拍，
     * 删掉任意一张都可能是用户想要的。
     */
    fun selectRedundant() {
        _state.update { snapshot ->
            val picked = snapshot.scan.groups
                .flatMap { group -> group.items.drop(1) }
                .map { it.uri }
                .toSet()
            snapshot.copy(selectedUris = picked)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedUris = emptySet()) }
    }

    /** 把选中项移入系统回收站。返回 null 表示没有选中项。 */
    fun trashSelected(): IntentSender? {
        val uris = _state.value.selectedUris.toList()
        if (uris.isEmpty()) return null
        return repository.trashRequest(uris)
    }

    /**
     * 系统对话框返回后重扫。
     *
     * [removed] 为 true 表示用户确认了移入回收站。这时先**乐观地**把已选内容
     * 从分组里摘掉：系统是异步落库的，授权框返回的瞬间立刻重扫拿到的还是旧数据，
     * 用户会看到「删完了但东西还在」。
     *
     * 摘掉之后再延迟重扫一次，与真实状态对齐。沿用原来的扫描范围，
     * 用户不必重新选一遍。
     */
    fun onTrashFinished(removed: Boolean) {
        val snapshot = _state.value

        if (removed) {
            val gone = snapshot.selectedUris
            _state.update { current ->
                current.copy(
                    scan = current.scan.copy(
                        groups = current.scan.groups
                            .map { group ->
                                group.copy(items = group.items.filterNot { it.uri in gone })
                            }
                            // 摘掉后不足两个的组不再是「一组重复」
                            .filter { it.items.size >= 2 },
                    ),
                    selectedUris = emptySet(),
                )
            }
        }

        viewModelScope.launch {
            delay(RECONCILE_DELAY_MS)
            scan(snapshot.kind, snapshot.albumId, snapshot.albumName)
        }
    }

    suspend fun loadThumbnail(uri: String): Bitmap? = repository.thumbnail(uri, THUMBNAIL_PX)

    private companion object {
        const val THUMBNAIL_PX = 320

        /** 等系统把删除落库之后再重扫，否则拿到的还是旧数据。 */
        const val RECONCILE_DELAY_MS = 1200L
    }
}
