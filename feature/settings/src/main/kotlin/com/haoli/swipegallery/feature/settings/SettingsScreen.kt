package com.haoli.swipegallery.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haoli.swipegallery.core.common.formatFileSize
import com.haoli.swipegallery.core.model.AppSettings
import com.haoli.swipegallery.core.model.MediaAlbum
import com.haoli.swipegallery.core.model.MoveTarget
import com.haoli.swipegallery.core.model.SortDirection
import com.haoli.swipegallery.core.model.SortField
import com.haoli.swipegallery.core.model.ThemeMode
import com.haoli.swipegallery.core.model.SwipeEffect

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 进入时重新统计：ViewModel 只创建一次，init 里那次数据早已过时
    LaunchedEffect(Unit) { viewModel.reloadStats() }

    BackHandler { onBack() }

    SettingsScreen(
        state = state,
        onBack = onBack,
        onOpenStats = onOpenStats,
        onPreloadCountChange = viewModel::setPreloadCount,
        onSortFieldChange = viewModel::setSortField,
        onSortDirectionChange = viewModel::setSortDirection,
        onMoveTargetChange = viewModel::setMoveTarget,
        onDuplicateThresholdChange = viewModel::setDuplicateThreshold,
        onThemeModeChange = viewModel::setThemeMode,
        onSwipeEffectChange = viewModel::setSwipeEffect,
        modifier = modifier,
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenStats: () -> Unit,
    onPreloadCountChange: (Int) -> Unit,
    onSortFieldChange: (SortField) -> Unit,
    onSortDirectionChange: (SortDirection) -> Unit,
    onMoveTargetChange: (MediaAlbum?) -> Unit,
    onDuplicateThresholdChange: (Long) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onSwipeEffectChange: (SwipeEffect) -> Unit,
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
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            SectionTitle("外观")

            SettingBlock(
                title = "主题",
                description = "跟随系统时，手机切浅色这里就是浅色，反之是深色。",
            ) {
                val modes = ThemeMode.entries
                ChipRow(
                    options = modes.map { it.label },
                    selectedIndex = modes.indexOf(state.settings.themeMode),
                    onSelect = { index -> onThemeModeChange(modes[index]) },
                )
            }

            SectionTitle("滑卡")

            SettingBlock(
                title = "预加载张数",
                description = "滑到某张时提前解码后面几张，数值越大越顺滑，内存占用也越高",
            ) {
                ChipRow(
                    options = AppSettings.PRELOAD_RANGE.map { it.toString() },
                    selectedIndex = state.settings.preloadCount - AppSettings.MIN_PRELOAD_COUNT,
                    onSelect = { index -> onPreloadCountChange(AppSettings.MIN_PRELOAD_COUNT + index) },
                )
            }

            MoveTargetBlock(
                target = state.settings.moveTarget,
                albums = state.albums,
                onSelect = onMoveTargetChange,
            )

            SettingBlock(
                title = "上滑删除的效果",
                description = "只在「上滑扔掉」这个方向生效。下滑是保留，语义不同，" +
                    "加动效反而像在强调一个不该强调的动作。",
            ) {
                val effects = SwipeEffect.entries
                ChipRow(
                    options = effects.map { it.label },
                    selectedIndex = effects.indexOf(state.settings.swipeEffect),
                    onSelect = { index -> onSwipeEffectChange(effects[index]) },
                )
            }

            SectionTitle("浏览")

            SettingBlock(title = "默认排序字段") {
                val fields = SortField.entries
                ChipRow(
                    options = fields.map { it.label },
                    selectedIndex = fields.indexOf(state.settings.defaultSortField),
                    onSelect = { index -> onSortFieldChange(fields[index]) },
                )
            }

            SettingBlock(title = "默认排序方向") {
                val directions = SortDirection.entries
                ChipRow(
                    options = directions.map { it.label },
                    selectedIndex = directions.indexOf(state.settings.defaultSortDirection),
                    onSelect = { index -> onSortDirectionChange(directions[index]) },
                )
            }

            SectionTitle("查重")

            SettingBlock(
                title = "疑似重复的判定阈值",
                description = "文件大小相差不超过这个值就算一组。调大更容易找到，" +
                    "但也更容易把连拍误判成重复；判据只有大小，不做内容比对。",
            ) {
                val options = AppSettings.DUPLICATE_THRESHOLD_OPTIONS
                ChipRow(
                    options = options.map { it.second },
                    selectedIndex = options.indexOfFirst {
                        it.first == state.settings.duplicateThresholdBytes
                    },
                    onSelect = { index -> onDuplicateThresholdChange(options[index].first) },
                )
            }

            SectionTitle("存储")

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clickable(onClick = onOpenStats),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "存储占用",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${state.stats.totalCount} 项 · " +
                                formatFileSize(state.stats.totalBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "查看",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingBlock(
    title: String,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        description?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/**
 * 下滑落点选择。
 *
 * 这一项存在的理由：下滑与左滑在「保留在原相册」时对文件的效果完全一样，
 * 手势重合等于浪费。选了目标相册之后，下滑才成为真正有效的归档动作。
 */
@Composable
private fun MoveTargetBlock(
    target: MoveTarget?,
    albums: List<MediaAlbum>,
    onSelect: (MediaAlbum?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "下滑保留",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = target?.albumName ?: "保留在原相册",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (expanded) "收起" else "更改",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(2.dp))
        Text(
            text = if (target == null) {
                "下滑不改动文件。选一个目标相册后，下滑会把照片移过去 —— " +
                    "这样「下滑归档」与「左滑跳过」才是两件不同的事。"
            } else {
                "下滑会把照片移动到「${target.albumName}」，退出查看器时统一申请授权并执行。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            TargetOption(
                label = "保留在原相册（不移动）",
                selected = target == null,
                onClick = { onSelect(null); expanded = false },
            )
            albums.forEach { album ->
                TargetOption(
                    label = "${album.name} · ${album.itemCount}",
                    // target 可空，必须用安全调用
                    selected = album.id == target?.albumId,
                    onClick = { onSelect(album); expanded = false },
                )
            }
            if (albums.isEmpty()) {
                Text(
                    text = "没有可用的相册",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun TargetOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text(
                    text = "已选",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Surface(
                shape = RoundedCornerShape(50),
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.clickable { onSelect(index) },
            ) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
