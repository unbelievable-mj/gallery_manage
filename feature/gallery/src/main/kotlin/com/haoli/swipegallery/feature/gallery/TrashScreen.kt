package com.haoli.swipegallery.feature.gallery

import android.app.Activity
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import com.haoli.swipegallery.core.model.MediaItem

/**
 * 回收站入口。
 *
 * 两个区块语义不同，界面上要分开：
 *  - **应用回收站**：滑卡删除的内容先到这里，文件尚未真正移除，可多选恢复或删除
 *  - **系统回收站**：在应用里点「删除」后内容交给系统，设备支持时能在这里看到并取回
 */
@Composable
fun TrashRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrashViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val purgeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onPurgeConfirmed()
        } else {
            // 用户在系统确认框里点了取消 —— 内容继续留在回收站，
            // 不能出现「点了取消东西却没了」
            viewModel.onPurgeDismissed()
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        viewModel.onSystemRestoreFinished()
    }

    BackHandler { onBack() }

    TrashScreen(
        state = state,
        onBack = onBack,
        onToggleSelection = viewModel::toggleSelection,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onRestore = viewModel::restoreSelected,
        onPurge = {
            val sender = viewModel.purgeSelected()
            if (sender != null) {
                purgeLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
        },
        onRestoreSystemItem = { item ->
            val sender = viewModel.restoreSystemItem(item)
            if (sender != null) {
                restoreLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
        },
        onLoadThumbnail = viewModel::loadThumbnail,
        modifier = modifier,
    )
}

@Composable
fun TrashScreen(
    state: TrashUiState,
    onBack: () -> Unit,
    onToggleSelection: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    onRestoreSystemItem: (MediaItem) -> Unit,
    onLoadThumbnail: suspend (String) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        TrashTopBar(
            count = state.appItems.size,
            allSelected = state.allSelected,
            hasSelection = state.hasSelection,
            onBack = onBack,
            onSelectAll = onSelectAll,
            onClearSelection = onClearSelection,
        )

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                state.isEmpty -> EmptyTrash()

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (state.appItems.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                title = "应用回收站",
                                subtitle = "文件尚未真正移除，恢复是瞬时的",
                            )
                        }
                        itemsIndexed(
                            items = state.appItems,
                            key = { _, item -> "app-${item.id}" },
                        ) { _, item ->
                            TrashCell(
                                item = item,
                                selected = item.id in state.selectedIds,
                                badge = null,
                                onClick = { onToggleSelection(item.id) },
                                onLoadThumbnail = onLoadThumbnail,
                            )
                        }
                    }

                    if (state.systemItems.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                title = "系统回收站",
                                subtitle = "点击任一项即可取回",
                            )
                        }
                        items(
                            items = state.systemItems,
                            key = { item -> "sys-${item.id}" },
                        ) { item ->
                            TrashCell(
                                item = item,
                                selected = false,
                                badge = "取回",
                                onClick = { onRestoreSystemItem(item) },
                                onLoadThumbnail = onLoadThumbnail,
                            )
                        }
                    }
                }
            }
        }

        if (state.hasSelection) {
            TrashActionBar(
                selectedCount = state.selectedIds.size,
                onRestore = onRestore,
                onPurge = onPurge,
            )
        }
    }
}

@Composable
private fun TrashTopBar(
    count: Int,
    allSelected: Boolean,
    hasSelection: Boolean,
    onBack: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
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
                text = "回收站",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = if (count == 0) "应用回收站为空" else "$count 项待处理",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (count > 0) {
            Text(
                text = if (hasSelection && !allSelected) "取消选择" else "全选",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(
                    onClick = { if (hasSelection && !allSelected) onClearSelection() else onSelectAll() },
                ),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrashCell(
    item: MediaItem,
    selected: Boolean,
    badge: String?,
    onClick: () -> Unit,
    onLoadThumbnail: suspend (String) -> Bitmap?,
) {
    var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.uri) {
        bitmap = onLoadThumbnail(item.uri)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
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

        // 选中态：整块压一层半透明遮罩 + 描边，比只画勾更醒目
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    .border(3.dp, MaterialTheme.colorScheme.primary),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .width(20.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        badge?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(vertical = 3.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TrashActionBar(
    selectedCount: Int,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Text(
                text = "删除后内容会移入系统回收站（设备支持时），否则将被永久移除",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
            )
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionButton(
                    label = "恢复 $selectedCount 项",
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    onClick = onRestore,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    label = "删除",
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = onPurge,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = container,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = content,
            )
        }
    }
}

@Composable
private fun EmptyTrash() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "回收站是空的",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "滑卡删除的内容会先进入这里，可以随时取回；\n只有点「删除」之后文件才会真正离开设备。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
