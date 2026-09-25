package com.haoli.swipegallery.feature.gallery

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.haoli.swipegallery.core.common.formatFileSize
import com.haoli.swipegallery.core.model.DuplicateGroup
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.core.model.MediaKind

/** 每组最多铺三列缩略图，超过就换行。 */
private const val COLUMNS_PER_GROUP = 3

/**
 * 查重入口。
 *
 * 扫描范围由调用方传入 —— 用户是在图库里选好类型与相册之后才点进来的，
 * 这个上下文必须延续过来，否则「在当前范围内查重」就无从谈起。
 */
@Composable
fun DuplicateRoute(
    kind: MediaKind,
    albumId: Long?,
    albumName: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DuplicateViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(kind, albumId) {
        viewModel.scan(kind, albumId, albumName)
    }

    val trashLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        // 只有用户确认了才乐观摘除；取消则内容原样保留
        viewModel.onTrashFinished(removed = result.resultCode == Activity.RESULT_OK)
    }

    BackHandler { onBack() }

    DuplicateScreen(
        state = state,
        onBack = onBack,
        onToggle = viewModel::toggle,
        onSelectRedundant = viewModel::selectRedundant,
        onClearSelection = viewModel::clearSelection,
        onTrash = {
            val sender = viewModel.trashSelected()
            if (sender != null) {
                trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
        },
        onLoadThumbnail = viewModel::loadThumbnail,
        modifier = modifier,
    )
}

@Composable
fun DuplicateScreen(
    state: DuplicateUiState,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectRedundant: () -> Unit,
    onClearSelection: () -> Unit,
    onTrash: () -> Unit,
    onLoadThumbnail: suspend (String) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "返回",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "疑似重复",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = buildString {
                        append(if (state.kind == MediaKind.IMAGE) "图片" else "视频")
                        append(" · ")
                        append(state.albumName ?: "全部相册")
                        append(" · 阈值 ")
                        append(formatFileSize(state.scan.thresholdBytes))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            state.loading -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            state.scan.isEmpty -> EmptyDuplicates(state.scan.scannedCount)

            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    SummaryBlock(
                        state = state,
                        onSelectRedundant = onSelectRedundant,
                        onClearSelection = onClearSelection,
                    )
                }

                state.scan.groups.forEachIndexed { index, group ->
                    item(key = "group-$index") {
                        GroupBlock(
                            index = index,
                            group = group,
                            showAlbumName = state.albumName == null,
                            selectedUris = state.selectedUris,
                            onToggle = onToggle,
                            onLoadThumbnail = onLoadThumbnail,
                        )
                    }
                }
            }
        }

        if (state.hasSelection) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Text(
                        text = "移入回收站后仍可恢复；在回收站里永久删除才会释放空间",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f).clickable(onClick = onClearSelection),
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "取消选择",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.weight(1f).clickable(onClick = onTrash),
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "移入回收站 ${formatFileSize(state.selectedBytes)}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryBlock(
    state: DuplicateUiState,
    onSelectRedundant: () -> Unit,
    onClearSelection: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "找到 ${state.scan.groupCount} 组 · 涉及 ${state.scan.fileCount} 个文件",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "每组只留一份的话，最多可释放 ${formatFileSize(state.scan.reclaimableBytes)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "判据只有文件大小接近，不做内容比对 —— 请对照缩略图自行确认",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.clickable(onClick = onSelectRedundant),
            ) {
                Text(
                    text = "每组保留最小的，其余选中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            if (state.hasSelection) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable(onClick = onClearSelection),
                ) {
                    Text(
                        text = "清空选择",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupBlock(
    index: Int,
    group: DuplicateGroup,
    showAlbumName: Boolean,
    selectedUris: Set<String>,
    onToggle: (String) -> Unit,
    onLoadThumbnail: suspend (String) -> Bitmap?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "第 ${index + 1} 组",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${group.count} 个 · 相差 ${formatFileSize(group.largestBytes - group.smallestBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "保留一份可释放 ${formatFileSize(group.reclaimableBytes)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        // 固定三列铺开，避免用实验性的 FlowRow
        group.items.chunked(COLUMNS_PER_GROUP).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { item ->
                    DuplicateCell(
                        item = item,
                        selected = item.uri in selectedUris,
                        showAlbumName = showAlbumName,
                        onClick = { onToggle(item.uri) },
                        onLoadThumbnail = onLoadThumbnail,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 补齐空位，避免最后一行被拉伸
                repeat(COLUMNS_PER_GROUP - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DuplicateCell(
    item: MediaItem,
    selected: Boolean,
    showAlbumName: Boolean,
    onClick: () -> Unit,
    onLoadThumbnail: suspend (String) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.uri) {
        bitmap = onLoadThumbnail(item.uri)
    }

    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val loaded = bitmap
            if (loaded != null) {
                Image(
                    bitmap = loaded.asImageBitmap(),
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                        .border(3.dp, MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = formatFileSize(item.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        // 全局扫描时才标相册名：指定相册时每个文件都是同一个相册，标了反而冗余
        if (showAlbumName) {
            Text(
                text = item.albumName.ifBlank { "未知相册" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EmptyDuplicates(scannedCount: Int) {
    // 这个函数没有 ColumnScope 接收者，用不了 weight；
    // 在 Column 里 fillMaxSize 会占满剩余空间，效果一样
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "没有找到疑似重复",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "已比对 $scannedCount 个文件，没有体积相近的一组。\n" +
                    "可以把设置里的阈值调大一些再试。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
