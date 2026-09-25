package com.haoli.swipegallery.feature.viewer

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.haoli.swipegallery.core.model.SwipeEffect
import kotlinx.coroutines.launch

/**
 * 查看器入口。
 *
 * 队列**不在这里注入** —— 由调用方在用户点击时通过 `viewModel.start(...)` 一次性写入。
 * 早先的写法是在这里用 `LaunchedEffect(items, startIndex)` 注入，那样旋转屏幕后
 * 组合会重建、`items` 变成空列表，直接把 ViewModel 里已有的队列覆盖掉，
 * 用户会看到「一旋转照片就没了」。
 *
 * 关闭时把待删队列提交到应用自己的回收站：只写记录，不碰文件，
 * 因此没有系统对话框、也不可能丢数据。
 */
@Composable
fun ViewerRoute(
    onClose: () -> Unit,
    onFlushed: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ViewerViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val swipeEffect by viewModel.swipeEffect.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }

    val context = LocalContext.current

    /** 收尾：提示本次结果、刷新列表、关闭查看器。 */
    suspend fun finish(moved: Int, denied: Boolean) {
        notifySummary(context, viewModel.summary(), moved = moved, denied = denied)
        onFlushed()
        onClose()
    }

    // 移动要改写文件的 RELATIVE_PATH，必须先拿到系统授予的写权限
    val moveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        scope.launch {
            val approved = result.resultCode == Activity.RESULT_OK
            val moved = if (approved) {
                viewModel.performMoves()
            } else {
                // 用户拒绝了写权限：文件留在原处，且不再追问第二次
                viewModel.discardMoves()
                0
            }
            finish(moved = moved, denied = !approved)
        }
    }

    // 移入系统回收站。无论用户同意与否都继续往下走：
    // 同意则内容已进回收站，取消则留在原处，两种情况的收尾一样。
    val trashLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        val sender = viewModel.moveRequest()
        if (sender != null) {
            moveLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            scope.launch { finish(moved = 0, denied = false) }
        }
    }

    val requestClose: () -> Unit = {
        if (!closing) {
            closing = true
            // 先把待删队列整批提交给系统回收站。
            // 滑卡时只记账不碰系统，就是为了在这里只弹一次授权框。
            val trashSender = viewModel.trashRequest()
            if (trashSender != null) {
                trashLauncher.launch(IntentSenderRequest.Builder(trashSender).build())
            } else {
                val moveSender = viewModel.moveRequest()
                if (moveSender != null) {
                    moveLauncher.launch(IntentSenderRequest.Builder(moveSender).build())
                } else {
                    scope.launch { finish(moved = 0, denied = false) }
                }
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
        onPageChanged = viewModel::onPageChanged,
        modifier = modifier,
    )
}

/**
 * 退出时给用户一个交代。
 *
 * 没有这个提示的话，用户滑完一轮完全不知道发生了什么 ——
 * 尤其是移动被系统授权框拦下时，文件其实没动，界面上却看不出来。
 */
private fun notifySummary(
    context: Context,
    summary: TriageSummary,
    moved: Int,
    denied: Boolean,
) {
    val parts = buildList {
        if (summary.deleted > 0) {
            add("移入回收站 ${summary.deleted} 项")
        }
        when {
            denied && summary.moved > 0 ->
                add("${summary.moved} 项未移动（已取消授权）")

            moved > 0 ->
                add("移到「${summary.moveTargetName.orEmpty()}」$moved 项")
        }
    }
    val text = if (parts.isEmpty()) "本次没有改动" else parts.joinToString("  ·  ")
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

@Composable
fun ViewerScreen(
    state: ViewerUiState,
    onSwipe: (SwipeDirection) -> Unit,
    onUndo: () -> Unit,
    onClose: () -> Unit,
    onLoadFullImage: suspend (String) -> Bitmap?,
    onPageChanged: (Int) -> Unit,
    swipeEffect: SwipeEffect = SwipeEffect.NONE,
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
                if (!state.started) {
                    // 队列注入前的空白帧。不能落进下面的 isFinished 分支，
                    // 否则点开图片会先闪一下「处理完了」。
                } else if (state.isFinished) {
                    FinishedState(progress = state.progress, onClose = onClose)
                } else {
                    TriagePager(
                        items = state.items,
                        initialIndex = state.currentIndex,
                        onSwipe = onSwipe,
                        onLoadFullImage = onLoadFullImage,
                        onPageChanged = onPageChanged,
                        swipeEffect = swipeEffect,
                    )
                }
            }

            state.current?.let { current ->
                InfoBar(item = current, moveTargetName = state.moveTargetName)
            }
        }
    }
}

@Composable
private fun TriagePager(
    items: List<MediaItem>,
    initialIndex: Int,
    onSwipe: (SwipeDirection) -> Unit,
    onLoadFullImage: suspend (String) -> Bitmap?,
    onPageChanged: (Int) -> Unit,
    swipeEffect: SwipeEffect,
) {
    // initialPage 必须取队列里的起始位置。
    // 不传的话 Pager 永远从第 0 页开始 —— 用户点第 50 张进去，也会从第一张开始处理，
    // 而且下面的 LaunchedEffect 会把 currentIndex 一并改回 0，起始位置就此丢失。
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        pageCount = { items.size },
    )

    // 通知外部当前页变化，用于铺预加载与更新进度
    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
    }

    // 删掉最后一张后当前页可能越界，把它拉回有效范围
    LaunchedEffect(items.size) {
        if (items.isNotEmpty() && pagerState.currentPage > items.lastIndex) {
            pagerState.scrollToPage(items.lastIndex)
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        // 不用自定义 key：删除后列表会缩短，而 Pager 仍可能按旧下标取 key，
        // items[page] 会越界崩溃；用 key 还会让 Pager 误以为页面发生了位移而动画跳转。
        // 单页的状态由 ViewerPage 内部按 uri 记忆，不依赖 Pager 的 key。
        val item = items.getOrNull(page) ?: return@HorizontalPager
        SwipeableCard(
            onSwipe = onSwipe,
            effect = swipeEffect,
            modifier = Modifier.fillMaxSize(),
        ) {
            ViewerPage(
                item = item,
                // Pager 会预组合相邻页，只有当前页才允许播放，
                // 否则会同时响起多个视频的声音
                isCurrent = page == pagerState.currentPage,
                onLoadFullImage = onLoadFullImage,
            )
        }
    }
}

@Composable
private fun ViewerPage(
    item: MediaItem,
    isCurrent: Boolean,
    onLoadFullImage: suspend (String) -> Bitmap?,
) {
    when (item.kind) {
        MediaKind.VIDEO -> VideoPlayer(
            uri = item.uri,
            active = isCurrent,
            modifier = Modifier.fillMaxSize(),
        )

        MediaKind.IMAGE -> ImagePage(item = item, onLoadFullImage = onLoadFullImage)
    }
}

@Composable
private fun ImagePage(
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
private fun InfoBar(item: MediaItem, moveTargetName: String?) {
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
            // 视频把时长放最前，这是它和图片最需要区分的属性
            item.durationMillis?.takeIf { it > 0L }?.let { add(formatDuration(it)) }
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
            text = "上滑删除  ·  " +
                (moveTargetName?.let { "下滑移到「$it」" } ?: "下滑保留") +
                "  ·  左右翻页",
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
