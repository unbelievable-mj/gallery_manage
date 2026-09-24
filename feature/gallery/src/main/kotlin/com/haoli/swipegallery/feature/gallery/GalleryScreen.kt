package com.haoli.swipegallery.feature.gallery

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haoli.swipegallery.core.common.formatDuration
import com.haoli.swipegallery.core.common.formatFileSize
import com.haoli.swipegallery.core.model.MediaAlbum
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.core.model.MediaKind
import com.haoli.swipegallery.core.model.SortDirection
import com.haoli.swipegallery.core.model.SortField

/**
 * 网格页入口。
 *
 * 负责权限申请与状态接线，把纯展示逻辑交给 [GalleryScreen]，
 * 这样后者可以在 Preview 与测试里直接驱动，不需要真实的 Context。
 */
@Composable
fun GalleryRoute(
    onOpenViewer: (startIndex: Int) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.onPermissionResult(hasMediaPermission(context))
    }

    LaunchedEffect(Unit) {
        val granted = hasMediaPermission(context)
        viewModel.onPermissionResult(granted)
        if (!granted) {
            permissionLauncher.launch(requiredMediaPermissions())
        }
    }

    GalleryScreen(
        state = state,
        partialAccess = isPartialMediaAccess(context),
        onRequestPermission = { permissionLauncher.launch(requiredMediaPermissions()) },
        onKindChange = viewModel::setKind,
        onSortFieldChange = viewModel::setSortField,
        onToggleSortDirection = viewModel::toggleSortDirection,
        onSelectAlbum = viewModel::selectAlbum,
        onLoadThumbnail = viewModel::loadThumbnail,
        onOpenViewer = onOpenViewer,
        onOpenTrash = onOpenTrash,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

@Composable
fun GalleryScreen(
    state: GalleryUiState,
    partialAccess: Boolean,
    onRequestPermission: () -> Unit,
    onKindChange: (MediaKind) -> Unit,
    onSortFieldChange: (SortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    onSelectAlbum: (Long?) -> Unit,
    onLoadThumbnail: suspend (String) -> Bitmap?,
    onOpenViewer: (Int) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // targetSdk 35+ 在 Android 15 上强制边到边，必须自己让出系统栏区域，
            // 否则顶部标题会被状态栏压住。
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        GalleryHeader(
            state = state,
            onOpenTrash = onOpenTrash,
            onOpenSettings = onOpenSettings,
        )

        SortBar(
            current = state.sort.field,
            direction = state.sort.direction,
            onSelect = onSortFieldChange,
            onToggleDirection = onToggleSortDirection,
        )

        KindTabs(kind = state.kind, onKindChange = onKindChange)

        // 只有一个相册时不显示这栏，否则纯属占地方
        if (state.albums.size > 1) {
            AlbumBar(
                albums = state.albums,
                selectedAlbumId = state.albumId,
                onSelect = onSelectAlbum,
            )
        }

        if (partialAccess) {
            PartialAccessBanner(onRequestPermission = onRequestPermission)
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                !state.permissionGranted -> PermissionPrompt(onRequestPermission = onRequestPermission)

                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                state.items.isEmpty() -> EmptyState(kind = state.kind)

                else -> MediaGrid(
                    items = state.items,
                    onLoadThumbnail = onLoadThumbnail,
                    onOpenViewer = onOpenViewer,
                )
            }
        }
    }
}

@Composable
private fun GalleryHeader(
    state: GalleryUiState,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "滑图",
                style = MaterialTheme.typography.headlineSmall,
            )
            val count = if (state.kind == MediaKind.IMAGE) {
                state.snapshot.imageCount
            } else {
                state.snapshot.videoCount
            }
            Text(
                text = "$count 项 · ${formatFileSize(state.snapshot.totalSizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HeaderAction(label = "回收站", onClick = onOpenTrash)
        HeaderAction(label = "设置", onClick = onOpenSettings)
    }
}

@Composable
private fun HeaderAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/**
 * 排序字段选择。
 *
 * 用户需求里明确提到「可以根据拍摄时间和文件大小自主排序」，
 * 所以这一栏放在首屏而不是藏进设置。
 */
@Composable
private fun SortBar(
    current: SortField,
    direction: SortDirection,
    onSelect: (SortField) -> Unit,
    onToggleDirection: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 升降序放在排序字段之前：它修饰的是后面选中的字段，
        // 位置靠前读起来才顺
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.clickable(onClick = onToggleDirection),
        ) {
            Text(
                text = if (direction == SortDirection.DESC) "降序" else "升序",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        SortField.entries.forEach { field ->
            SelectableChip(
                label = field.label,
                selected = field == current,
                onClick = { onSelect(field) },
            )
        }
    }
}

@Composable
private fun KindTabs(
    kind: MediaKind,
    onKindChange: (MediaKind) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SegmentedTab(
            label = "图片",
            selected = kind == MediaKind.IMAGE,
            onClick = { onKindChange(MediaKind.IMAGE) },
            modifier = Modifier.weight(1f),
        )
        SegmentedTab(
            label = "视频",
            selected = kind == MediaKind.VIDEO,
            onClick = { onKindChange(MediaKind.VIDEO) },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 相册选择栏。
 *
 * 用户需求里明确提到「也可以选择某个相册进行处理」——
 * 选定相册后，滑卡队列就是该相册的内容，而不是全库。
 */
@Composable
private fun AlbumBar(
    albums: List<MediaAlbum>,
    selectedAlbumId: Long?,
    onSelect: (Long?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectableChip(
            label = "全部",
            selected = selectedAlbumId == null,
            onClick = { onSelect(null) },
        )
        albums.forEach { album ->
            SelectableChip(
                label = "${album.name} · ${album.itemCount}",
                selected = album.id == selectedAlbumId,
                onClick = { onSelect(album.id) },
            )
        }
    }
}

@Composable
private fun SegmentedTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Box(
            modifier = Modifier.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.secondary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun MediaGrid(
    items: List<MediaItem>,
    onLoadThumbnail: suspend (String) -> Bitmap?,
    onOpenViewer: (Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> item.id },
        ) { index, item ->
            MediaCell(
                item = item,
                onLoadThumbnail = onLoadThumbnail,
                onClick = { onOpenViewer(index) },
            )
        }
    }
}

@Composable
private fun MediaCell(
    item: MediaItem,
    onLoadThumbnail: suspend (String) -> Bitmap?,
    onClick: () -> Unit,
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

        val duration = item.durationMillis
        if (item.kind == MediaKind.VIDEO && duration != null && duration > 0L) {
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun PartialAccessBanner(onRequestPermission: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "当前只允许访问你选中的部分照片",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "全部允许",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onRequestPermission),
            )
        }
    }
}

@Composable
private fun PermissionPrompt(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "需要访问你的照片和视频",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "滑图只在本机处理你的文件，不会上传任何内容。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onRequestPermission),
            ) {
                Text(
                    text = "授予权限",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(kind: MediaKind) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (kind == MediaKind.IMAGE) "本机没有找到图片" else "本机没有找到视频",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
