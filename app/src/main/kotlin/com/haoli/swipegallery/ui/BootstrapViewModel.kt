package com.haoli.swipegallery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haoli.swipegallery.BuildConfig
import com.haoli.swipegallery.core.common.formatFileSize
import com.haoli.swipegallery.core.data.MediaRepository
import com.haoli.swipegallery.core.model.LibrarySnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * M0 骨架页面的状态。
 *
 * 其中构建信息全部取自 [BuildConfig]，用途是让装到手机上的 APK
 * 能自证身份：一看就知道拿到的是哪个版本、是不是 release 包。
 */
data class BootstrapUiState(
    val versionName: String = "",
    val versionCode: Int = 0,
    val buildType: String = "",
    val isDebuggable: Boolean = false,
    val applicationId: String = "",
    val snapshot: LibrarySnapshot = LibrarySnapshot(0, 0, 0L, isStub = true),
    val totalSizeLabel: String = "—",
)

/**
 * 这个 ViewModel 在 M0 阶段承担的是一次「连通性测试」：
 * 它通过构造器注入拿到 [MediaRepository]，而 MediaRepository 的实现
 * 位于另一个 Gradle 模块（core:data）。
 *
 * 只要这个类能编译并被实例化，就证明：
 * 多模块依赖、Hilt 模块绑定、KSP 代码生成、Compose 与 ViewModel 的桥接全部正常。
 */
@HiltViewModel
class BootstrapViewModel @Inject constructor(
    repository: MediaRepository,
) : ViewModel() {

    val state: StateFlow<BootstrapUiState> = repository.observeSnapshot()
        .map { snapshot ->
            BootstrapUiState(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                buildType = BuildConfig.BUILD_TYPE,
                isDebuggable = BuildConfig.DEBUG,
                applicationId = BuildConfig.APPLICATION_ID,
                snapshot = snapshot,
                totalSizeLabel = if (snapshot.isStub) "—" else formatFileSize(snapshot.totalSizeBytes),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = BootstrapUiState(),
        )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
