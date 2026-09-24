package com.haoli.swipegallery.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haoli.swipegallery.core.common.formatFileSize
import com.haoli.swipegallery.core.model.AlbumUsage
import com.haoli.swipegallery.core.model.MediaKind
import com.haoli.swipegallery.core.model.StorageStats

/** 相册列表最多展示多少条，避免一个几百项的长列表把页面撑爆。 */
private const val MAX_ALBUM_ROWS = 15

@Composable
fun StatsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    StatsScreen(
        stats = state.stats,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun StatsScreen(
    stats: StorageStats,
    onBack: () -> Unit,
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
                text = "存储占用",
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = formatFileSize(stats.totalBytes),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "${stats.totalCount} 项媒体文件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))
            ProportionBar(
                segments = listOf(
                    stats.imageBytes to MaterialTheme.colorScheme.primary,
                    stats.videoBytes to MaterialTheme.colorScheme.secondary,
                ),
                total = stats.totalBytes,
            )
            Spacer(Modifier.height(16.dp))

            UsageRow(
                label = "图片",
                detail = "${stats.imageCount} 项",
                bytes = stats.imageBytes,
                accent = MaterialTheme.colorScheme.primary,
            )
            UsageRow(
                label = "视频",
                detail = "${stats.videoCount} 项",
                bytes = stats.videoBytes,
                accent = MaterialTheme.colorScheme.secondary,
            )
            UsageRow(
                label = "回收站",
                detail = "${stats.trashedCount} 项",
                bytes = stats.trashedBytes,
                accent = MaterialTheme.colorScheme.error,
                // 这一行是最容易被误解的：删除不等于释放空间
                note = if (stats.trashedCount > 0) "仍占用磁盘，彻底删除后才释放" else null,
            )

            if (stats.albums.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "按相册",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(10.dp))

                val shown = stats.albums.take(MAX_ALBUM_ROWS)
                val maxBytes = shown.maxOfOrNull { it.bytes }?.coerceAtLeast(1L) ?: 1L
                shown.forEach { album ->
                    AlbumRow(album = album, maxBytes = maxBytes)
                }

                if (stats.albums.size > shown.size) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "另有 ${stats.albums.size - shown.size} 个相册未显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageRow(
    label: String,
    detail: String,
    bytes: Long,
    accent: Color,
    note: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(8.dp)
                .background(accent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = formatFileSize(bytes),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AlbumRow(album: AlbumUsage, maxBytes: Long) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = album.albumName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (album.kind == MediaKind.VIDEO) {
                Text(
                    text = "视频",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                text = "${album.itemCount} 项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = formatFileSize(album.bytes),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(5.dp))
        // 以当前列表最大值为基准，条形长度才有可比性
        val fraction = (album.bytes.toFloat() / maxBytes).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(2.dp),
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** 图片与视频的占比条。总量为 0 时整体退化为灰条，不做除零。 */
@Composable
private fun ProportionBar(segments: List<Pair<Long, Color>>, total: Long) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (total <= 0L) {
                Spacer(Modifier.fillMaxSize())
            } else {
                segments.forEach { (bytes, color) ->
                    if (bytes > 0L) {
                        // 高度用 fillMaxHeight 而不是 fillMaxSize：
                        // fillMaxSize 会同时把宽度设回满格，把前面的比例覆盖掉
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(bytes.toFloat() / total)
                                .background(color),
                        )
                    }
                }
            }
        }
    }
}
