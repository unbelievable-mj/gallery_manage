package com.haoli.swipegallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haoli.swipegallery.core.data.settings.SettingsRepository
import com.haoli.swipegallery.core.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 应用级状态。目前只有主题模式。
 *
 * 单独放一个 ViewModel 而不是塞进业务 ViewModel：主题要在 Activity 最外层、
 * 早于任何页面确定下来，跟网格 / 查看器的生命周期没关系。
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settingsRepository.settings
        .map { it.themeMode }
        // Eagerly 而不是 WhileSubscribed：主题必须在首帧就定下来，
        // 否则会先按浅色画一帧再切成深色，肉眼能看到闪一下
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    /**
     * 是否已确认免责声明。
     *
     * 初始值给 true（即「先不弹」），等读到真实设置再决定 ——
     * 反过来的话每次冷启动都会先闪一下弹窗。
     */
    val disclaimerAccepted: StateFlow<Boolean> = settingsRepository.settings
        .map { it.disclaimerAccepted }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun acceptDisclaimer() {
        viewModelScope.launch { settingsRepository.setDisclaimerAccepted(true) }
    }
}
