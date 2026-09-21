package com.haoli.swipegallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haoli.swipegallery.core.designsystem.theme.SwipeGalleryTheme
import com.haoli.swipegallery.ui.BootstrapScreen
import com.haoli.swipegallery.ui.BootstrapViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * 单 Activity 宿主。
 *
 * 导航在 M1 接入 Navigation Compose 后接管；M0 阶段直接展示骨架页。
 * 这里用 `viewModel()` 而非 `hiltViewModel()`：@AndroidEntryPoint 已经把
 * 默认的 ViewModelProvider.Factory 换成 Hilt 版本，普通 API 即可解析
 * @HiltViewModel 标注的类，少依赖一个实验性接口。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            SwipeGalleryTheme {
                val viewModel: BootstrapViewModel = viewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()

                BootstrapScreen(state = state)
            }
        }
    }
}
