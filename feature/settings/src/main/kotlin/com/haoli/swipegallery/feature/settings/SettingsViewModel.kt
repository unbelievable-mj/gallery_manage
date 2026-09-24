package com.haoli.swipegallery.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haoli.swipegallery.core.data.MediaRepository
import com.haoli.swipegallery.core.data.settings.SettingsRepository
import com.haoli.swipegallery.core.model.AppSettings
import com.haoli.swipegallery.core.model.MediaAlbum
import com.haoli.swipegallery.core.model.MediaKind
import com.haoli.swipegallery.core.model.MoveTarget
import com.haoli.swipegallery.core.model.SortDirection
import com.haoli.swipegallery.core.model.SortField
import com.haoli.swipegallery.core.model.StorageStats
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val stats: StorageStats = StorageStats(),
    /** 可选的目标相册，供「下滑保留」选择。 */
    val albums: List<MediaAlbum> = emptyList(),
    val loading: Boolean = true,
)

/**
 * 设置页与存储统计页共用。
 *
 * 两个页面的数据都来自本地（DataStore + MediaStore），没有网络往返，
 * 因此不需要各自的加载状态机，合用一个 ViewModel 更简单。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val mediaRepository: MediaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { loaded ->
                _state.update { it.copy(settings = loaded, loading = false) }
            }
        }
        reloadStats()
    }

    /**
     * 重新统计并拉取相册列表。
     *
     * 两者都要扫全库，不适合做成持续订阅 —— 只在进入页面时算一次。
     * 查询在部分 ROM 上可能抛异常，这里兜住让页面退化成空数据而不是崩掉。
     */
    fun reloadStats() {
        viewModelScope.launch {
            val stats = try {
                mediaRepository.observeStats().first()
            } catch (_: Exception) {
                StorageStats()
            }
            _state.update { it.copy(stats = stats, loading = false) }
        }
        viewModelScope.launch {
            val albums = try {
                // 图片相册为主；视频相册同样可以当落点，一并列出
                mediaRepository.observeAlbums(MediaKind.IMAGE).first() +
                    mediaRepository.observeAlbums(MediaKind.VIDEO).first()
            } catch (_: Exception) {
                emptyList()
            }
            // 没有可用目录路径的相册不能作为落点，直接过滤掉，
            // 免得用户选中之后移动静默失败
            _state.update {
                it.copy(albums = albums.filter { album -> album.relativePath.isNotBlank() })
            }
        }
    }

    fun setPreloadCount(count: Int) {
        viewModelScope.launch { settingsRepository.setPreloadCount(count) }
    }

    /**
     * 设置下滑落点。传 null 表示回到「保留在原相册」。
     *
     * 相册缺少目录路径时不写入 —— 宁可保持原设置，也不要存一个移不动的目标。
     */
    fun setMoveTarget(album: MediaAlbum?) {
        viewModelScope.launch {
            val target = album
                ?.takeIf { it.relativePath.isNotBlank() }
                ?.let {
                    MoveTarget(
                        albumId = it.id,
                        albumName = it.name,
                        relativePath = it.relativePath,
                    )
                }
            settingsRepository.setMoveTarget(target)
        }
    }

    fun setSortField(field: SortField) {
        viewModelScope.launch {
            settingsRepository.setDefaultSort(field, _state.value.settings.defaultSortDirection)
        }
    }

    fun setSortDirection(direction: SortDirection) {
        viewModelScope.launch {
            settingsRepository.setDefaultSort(_state.value.settings.defaultSortField, direction)
        }
    }

    /** 查重阈值：文件大小相差不超过它就算「疑似重复」。 */
    fun setDuplicateThreshold(bytes: Long) {
        viewModelScope.launch { settingsRepository.setDuplicateThreshold(bytes) }
    }
}
