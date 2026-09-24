package com.haoli.swipegallery.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haoli.swipegallery.core.data.MediaRepository
import com.haoli.swipegallery.core.data.settings.SettingsRepository
import com.haoli.swipegallery.core.model.AppSettings
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
     * 重新统计。
     *
     * 统计要扫全库，不适合做成持续订阅 —— 只在进入页面时算一次。
     * 查询在部分 ROM 上可能抛异常，这里兜住让页面退化成全零而不是崩掉。
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
    }

    fun setPreloadCount(count: Int) {
        viewModelScope.launch { settingsRepository.setPreloadCount(count) }
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
}
