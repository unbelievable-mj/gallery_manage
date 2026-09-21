package com.haoli.swipegallery.feature.viewer

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haoli.swipegallery.core.common.formatDateTime
import com.haoli.swipegallery.core.common.formatDuration
import com.haoli.swipegallery.core.common.formatFileSize
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.core.model.MediaKind
import com.haoli.swipegallery.core.model.TriageProgress

/**
 * 查看器入口。
 *
 * 负责把「本地待删队列」提交给系统（两阶段提交的阶段二），
 * 并保证刷新回调一定发生在提交之后 —— 否则列表会与系统真实状态不一致。
 */
@Composable
fun ViewerRoute(
    items: List<MediaItem>,
    startIndex: Int,
    onClose: () -> Unit,
    onFlushed: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ViewerViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var closing by remember { mutableStateOf(false) }

    // 阶段二的结果回调。无论用户是否同意，都要刷新一次：
    // 同意则文件已进回收站，拒绝则本地待删队列已清空、列表需要还原。
    val trashLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        onFlushed()
        onClose()
    }

    LaunchedEffect(items, startIndex) {
        viewModel.start(items, startIndex)
    }

    val requestClose: () -> Unit = {
        if (!closing) {
            closing = true
            val sender = viewModel.flushToSystemTrash()
            if (sender != null) {
                trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } else {
                onClose()
            }
        }
    }

    BackHandler { requestClose() }

    ViewerScreen(
        state = state,
        onSwipe = viewModel::applySwipe,
        onUndo = viewModel::undo,
        onClose = requestClose,
        onLoadFullImage = viewModel::loadFullImage,
        modifier = modifier,
    )
}

@Composable
fun ViewerScreen(
    state: ViewerUiState,
    onSwipe: (SwipeDirection) -> Unit,
    onUndo: () -> Unit,
    onClose: () -> Unit,
    onLoadFullImage: suspend (String) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    // 黑色底铺满整屏（含系统栏区域），内容让出系统栏。
    // targetSdk 35+ 在 Android 15 上强制边到边，不处理的话顶部按钮会顶到状态栏里。
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            ViewerTopBar(
                progress = state.progress,
                canUndo = state.canUndo,
                lastActionLabel = state.lastActionLabel,
                onUndo = onUndo,
                onClose = onClose,
            )

            Box(modifier = Modifier.weight(1f)) {
                if (state.isFinished) {
                    FinishedState(progress = state.progress, onClose = onClose)
                } else {
                    TriagePager(
                        items = state.items,
                        onSwipe = onSwipe,
                        onLoadFullImage = onLoadFullImage,
                    )
                }
            }

            state.current?.let { current ->
                InfoBar(item = current)
            }
        }
    }
}

@Composable
private fun TriagePager(
    items: List<MediaItem>,
    onSwipe: (SwipeDirection) -> Unit,
    onLoadFullImage: suspend (String) -> Bitmap?,
) {
    val pagerState = rememberPagerState(pageCount = { items.size })

    // 删掉最后一张后当前页可能越界，把它拉回有效范围
    LaunchedEffect(items.size) {
        if (items.isNotEmpty() && pagerState.currentPage > items.lastIndex) {
            pagerState.scrollToPage(items.lastIndex)
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        key = { index -> items[index].id },
    ) { page ->
        SwipeableCard(
            onSwipe = onSwipe,
            modifier = Modifier.fillMaxSize(),
        ) {
            ViewerPage(
                item = items[page],
                onLoadFullImage = onLoadFullImage,
            )
        }
    }
}

@Composable
private fun ViewerPage(
    item: MediaItem,
    onLoadFullImage: suspend (String) -> Bitmap?,
) {
    var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.uri) {
        bitmap = onLoadFullImage(item.uri)
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }

        if (item.kind == MediaKind.VIDEO) {
            VideoBadge(item = item)
        }
    }
}

/**
 * 视频暂以首帧静态展示。
 * 真正的播放能力（Media3 ExoPlayer）是下一步的工作 —— 这里先把位置和交互占住，
 * 避免用户看到视频时误以为是图片。
 *
 * 声明为 [BoxScope] 扩展：`Modifier.align` 只在 Box 作用域内可用，
 * 写在普通 composable 里会直接编译不过。
 */
@Composable
private fun BoxScope.VideoBadge(item: MediaItem) {
    val duration = item.durationMillis
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.6f),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 24.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "视频",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
            if (duration != null && duration > 0L) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun ViewerTopBar(
    progress: TriageProgress,
    canUndo: Boolean,
    lastActionLabel: String?,
    onUndo: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "关闭",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.clickable(onClick = onClose),
            )

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "已处理 ${progress.processed} / ${progress.total}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                Spacer(Modifier.height(6.dp))
                ProgressTrack(fraction = progress.fraction)
            }

            Spacer(Modifier.width(16.dp))

            Text(
                text = "撤销",
                style = MaterialTheme.typography.labelLarge,
                color = if (canUndo) Color.White else Color.White.copy(alpha = 0.35f),
                modifier = Modifier.clickable(enabled = canUndo, onClick = onUndo),
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "已删 ${progress.deleted} · 已保留 ${progress.kept}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            lastActionLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** 自绘进度条。Material3 的 LinearProgressIndicator 在两个大版本间改过签名，自己画更稳。 */
@Composable
private fun ProgressTrack(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(3.dp)
                .background(Color.White, RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * 底部概览信息栏 —— 用户明确要求的「拍摄/创建时间 + 文件占用空间」。
 */
@Composable
private fun InfoBar(item: MediaItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(
            text = formatDateTime(item.effectiveDateMillis),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
        )
        Spacer(Modifier.height(4.dp))

        val resolution = if (item.width > 0 && item.height > 0) {
            "${item.width} × ${item.height}"
        } else {
            null
        }
        val parts = buildList {
            add(formatFileSize(item.sizeBytes))
            resolution?.let { add(it) }
            if (item.albumName.isNotBlank()) add(item.albumName)
        }

        Text(
            text = parts.joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = "上滑删除  ·  下滑保留  ·  左右翻页",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun FinishedState(progress: TriageProgress, onClose: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "处理完了",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "共 ${progress.total} 项 · 删除 ${progress.deleted} · 保留 ${progress.kept}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.White.copy(alpha = 0.15f),
                modifier = Modifier.clickable(onClick = onClose),
            ) {
                Text(
                    text = "返回相册",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}
