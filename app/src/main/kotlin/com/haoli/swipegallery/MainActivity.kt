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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haoli.swipegallery.core.designsystem.theme.SwipeGalleryTheme
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.feature.gallery.GalleryRoute
import com.haoli.swipegallery.feature.gallery.GalleryViewModel
import com.haoli.swipegallery.feature.viewer.ViewerRoute
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

    // null 表示查看器未打开
    var viewerStartIndex by remember { mutableStateOf<Int?>(null) }
    var viewerQueue by remember { mutableStateOf(emptyList<MediaItem>()) }

    val startIndex = viewerStartIndex
    if (startIndex == null) {
        GalleryRoute(
            onOpenViewer = { index ->
                // 把当前列表整体交给查看器，之后由它自己维护队列
                viewerQueue = galleryState.items
                viewerStartIndex = index
            },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        ViewerRoute(
            items = viewerQueue,
            startIndex = startIndex,
            onClose = { viewerStartIndex = null },
            // 阶段二提交后必须重新查询，让网格与系统真实状态对齐
            onFlushed = { galleryViewModel.reload() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
