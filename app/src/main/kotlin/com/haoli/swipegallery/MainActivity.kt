package com.haoli.swipegallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haoli.swipegallery.core.designsystem.theme.SwipeGalleryTheme
import com.haoli.swipegallery.feature.gallery.GalleryRoute
import com.haoli.swipegallery.feature.gallery.GalleryViewModel
import com.haoli.swipegallery.feature.gallery.TrashRoute
import com.haoli.swipegallery.feature.viewer.ViewerRoute
import com.haoli.swipegallery.feature.viewer.ViewerViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * 单 Activity 宿主。
 *
 * 用 `viewModel()` 而非 `hiltViewModel()`：@AndroidEntryPoint 已经把默认的
 * ViewModelProvider.Factory 换成 Hilt 版本，普通 API 即可解析 @HiltViewModel 标注的类，
 * 少依赖一个接口。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            SwipeGalleryTheme {
                SwipeGalleryHost()
            }
        }
    }
}

/**
 * 应用宿主。
 *
 * 目前只有「网格」与「查看器」两个目的地，用状态切换而非 Navigation Compose：
 * 查看器需要拿到网格已经查好的整个队列，状态切换可以直接传递这个列表，
 * 走导航参数则要重新查询一遍。等目的地变多（回收站、统计、设置）再引入导航库。
 */
@Composable
private fun SwipeGalleryHost() {
    val context = LocalContext.current
    var crashReport by remember { mutableStateOf(readCrashReport(context)) }

    val report = crashReport
    if (report != null) {
        // 上次是异常退出。先把堆栈摆到用户面前，否则无从定位。
        CrashScreen(
            report = report,
            onDismiss = {
                clearCrashReport(context)
                crashReport = null
            },
        )
        return
    }

    val galleryViewModel: GalleryViewModel = viewModel()
    val galleryState by galleryViewModel.state.collectAsStateWithLifecycle()
    val viewerViewModel: ViewerViewModel = viewModel()

    // 用 rememberSaveable 记住目的地：旋转屏幕后不会莫名其妙跳回网格。
    // 查看器队列与回收站列表分别存在各自的 ViewModel（Activity 作用域）里，不会丢。
    // 存字符串而非枚举，避免依赖 rememberSaveable 对枚举的序列化支持。
    var destinationName by rememberSaveable { mutableStateOf(Destination.GALLERY.name) }
    val destination = runCatching { Destination.valueOf(destinationName) }
        .getOrDefault(Destination.GALLERY)

    when (destination) {
        Destination.GALLERY -> GalleryRoute(
            onOpenViewer = { index ->
                // 点击时才注入队列。若放在 ViewerRoute 里用 LaunchedEffect 注入，
                // 旋转屏幕后组合重建会用空列表覆盖掉已有队列。
                viewerViewModel.start(galleryState.items, index)
                destinationName = Destination.VIEWER.name
            },
            onOpenTrash = { destinationName = Destination.TRASH.name },
            modifier = Modifier.fillMaxSize(),
        )

        Destination.VIEWER -> ViewerRoute(
            onClose = { destinationName = Destination.GALLERY.name },
            // 阶段二提交后必须重新查询，让网格与系统真实状态对齐
            onFlushed = { galleryViewModel.reload() },
            modifier = Modifier.fillMaxSize(),
        )

        Destination.TRASH -> TrashRoute(
            onBack = {
                // 回收站里可能取回了内容，回到网格前要重新查询
                galleryViewModel.reload()
                destinationName = Destination.GALLERY.name
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 三个目的地。用名称字符串持久化，避免依赖枚举的序列化行为。 */
private enum class Destination { GALLERY, VIEWER, TRASH }
