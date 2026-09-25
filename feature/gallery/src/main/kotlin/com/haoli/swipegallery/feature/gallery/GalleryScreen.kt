package com.haoli.swipegallery.feature.gallery

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haoli.swipegallery.core.common.formatDuration
import com.haoli.swipegallery.core.common.formatFileSize
import com.haoli.swipegallery.core.model.DateRange
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
    onOpenDuplicates: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        DateRangeDialog(
            current = state.dateRange,
            onDismiss = { showDatePicker = false },
            onConfirm = { range ->
                viewModel.setDateRange(range)
                showDatePicker = false
            },
            onClear = {
                viewModel.setDateRange(null)
                showDatePicker = false
            },
        )
    }

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
        onOpenDatePicker = { showDatePicker = true },
        onClearDateRange = { viewModel.setDateRange(null) },
        onZoomColumns = viewModel::zoomColumns,
        onLoadThumbnail = viewModel::loadThumbnail,
        onOpenViewer = onOpenViewer,
        onOpenTrash = onOpenTrash,
        onOpenSettings = onOpenSettings,
        onOpenDuplicates = onOpenDuplicates,
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
    onOpenDatePicker: () -> Unit,
    onClearDateRange: () -> Unit,
    onZoomColumns: (Int) -> Unit,
    onLoadThumbnail: suspend (String) -> Bitmap?,
    onOpenViewer: (Int) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDuplicates: () -> Unit,
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
            onOpenDuplicates = onOpenDuplicates,
            onOpenSettings = onOpenSettings,
        )

        DateFilterBar(
            range = state.dateRange,
            onOpen = onOpenDatePicker,
            onClear = onClearDateRange,
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

        if (state.trashCount > 0) {
            TrashBanner(
                count = state.trashCount,
                bytes = state.trashBytes,
                onOpenTrash = onOpenTrash,
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

                state.items.isEmpty() -> EmptyState(
                    kind = state.kind,
                    dateFiltered = state.dateRange != null,
                )

                else -> MediaGrid(
                    items = state.items,
                    onLoadThumbnail = onLoadThumbnail,
                    onOpenViewer = onOpenViewer,
                    onZoomColumns = onZoomColumns,
                    columns = state.columns,
                )
            }
        }
    }
}

@Composable
private fun GalleryHeader(
    state: GalleryUiState,
    onOpenTrash: () -> Unit,
    onOpenDuplicates: () -> Unit,
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

        HeaderAction(label = "查重", onClick = onOpenDuplicates)
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

/** 双指缩放换一档所需的累积幅度。留出余量，避免轻微抖动就跳档。 */
private const val ZOOM_OUT_STEP = 1.25f
private const val ZOOM_IN_STEP = 0.8f

/**
 * 日期筛选条。
 *
 * 只占一行：未筛选时是一个「日期：全部」的入口，选好之后直接显示区间并可一键清除。
 * 筛选条件始终可见，用户不会出现「怎么少了这么多照片」的困惑。
 */
@Composable
private fun DateFilterBar(
    range: DateRange?,
    onOpen: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = if (range == null) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.clickable(onClick = onOpen),
        ) {
            Text(
                text = if (range == null) {
                    "日期：全部"
                } else {
                    "${formatDay(range.startMillis)} ~ ${formatDay(range.endMillis)}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (range == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        if (range != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "清除",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onClear),
            )
        }
    }
}

/** 把毫秒时间戳按设备时区格式化成 `2026-09-01`。 */
private fun formatDay(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .toString()

/**
 * 回收站提示条。
 *
 * 存在的理由：删除后空间不会立刻释放（内容进了回收站），
 * 界面上如果不显示「还有多少空间被占着」，用户会以为删除根本没生效 ——
 * 这正是之前踩过的坑。
 */
@Composable
private fun TrashBanner(count: Int, bytes: Long, onOpenTrash: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clickable(onClick = onOpenTrash),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "回收站 $count 项 · 占用 ${formatFileSize(bytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "这些内容仍占着磁盘，永久删除后才释放",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "去清理",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
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
    columns: Int,
    onLoadThumbnail: suspend (String) -> Bitmap?,
    onOpenViewer: (Int) -> Unit,
    onZoomColumns: (Int) -> Unit,
) {
    // 缩放累积量：手指张开到一定幅度才换一档，避免轻微抖动就来回调档
    var accumulated by remember { mutableFloatStateOf(1f) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // 手写双指缩放而不是用 detectTransformGestures：
                // 后者把单指拖拽也当作平移处理并消费掉，会把列表滚动一起吃掉。
                // 这里只在真的检测到缩放（zoom != 1）时才消费事件，
                // 单指上下滚动完全不受影响。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        if (zoom != 1f) {
                            accumulated *= zoom
                            when {
                                // 手指张开 → 放大 → 列数减少、格子变大
                                accumulated >= ZOOM_OUT_STEP -> {
                                    onZoomColumns(-1)
                                    accumulated = 1f
                                }
                                // 手指捏合 → 缩小 → 列数增加
                                accumulated <= ZOOM_IN_STEP -> {
                                    onZoomColumns(1)
                                    accumulated = 1f
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
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

/**
 * 空状态。
 *
 * 要区分「图库本来就空」与「日期筛选没筛出东西」—— 后者说明的是筛选条件，
 * 不是图库状态，混为一谈会让用户以为照片没了。
 */
@Composable
private fun EmptyState(kind: MediaKind, dateFiltered: Boolean) {
    val noun = if (kind == MediaKind.IMAGE) "图片" else "视频"
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (dateFiltered) "该日期范围内没有$noun" else "本机没有找到$noun",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
