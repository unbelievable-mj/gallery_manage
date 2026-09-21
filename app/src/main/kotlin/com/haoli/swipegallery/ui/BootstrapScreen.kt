package com.haoli.swipegallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.haoli.swipegallery.core.designsystem.theme.SwipeGalleryTheme

private enum class MilestoneStatus(val label: String, val color: Color) {
    DONE("已完成", Color(0xFF639922)),
    NEXT("下一步", Color(0xFFEF9F27)),
    PLANNED("待开发", Color(0xFF888780)),
}

private data class Milestone(
    val id: String,
    val title: String,
    val status: MilestoneStatus,
)

private val milestones = listOf(
    Milestone("M0", "项目骨架 · CI/CD 签名发布", MilestoneStatus.DONE),
    Milestone("M1", "媒体库读取 · 图片/视频网格", MilestoneStatus.NEXT),
    Milestone("M2", "相册接入 · 范围选择与排序", MilestoneStatus.PLANNED),
    Milestone("M3", "查看器 · 视频播放 · 信息栏", MilestoneStatus.PLANNED),
    Milestone("M4", "滑卡手势 · 上滑删除 / 下滑保留", MilestoneStatus.PLANNED),
    Milestone("M5", "预加载缓存 · 多级撤销", MilestoneStatus.PLANNED),
    Milestone("M6", "系统回收站集成 · 免弹窗授权", MilestoneStatus.PLANNED),
    Milestone("M7", "存储统计 · 设置项", MilestoneStatus.PLANNED),
    Milestone("M8", "性能打磨 · 首发 v1.0.0", MilestoneStatus.PLANNED),
)

/**
 * M0 骨架页。
 *
 * 它不是最终形态，而是让「从 GitHub Release 下载安装」这条链路
 * 有一个可以肉眼确认的落点：能打开、能看到版本号和构建类型，
 * 就说明签名、打包、发布全部正确。
 */
@Composable
fun BootstrapScreen(state: BootstrapUiState) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Text(
                text = "滑图",
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "SwipeGallery · 本地图片与视频过筛工具",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            InfoCard(title = "构建信息") {
                InfoRow("版本", "${state.versionName} (${state.versionCode})")
                InfoRow("构建类型", state.buildType)
                InfoRow("可调试", if (state.isDebuggable) "是" else "否")
                InfoRow("包名", state.applicationId, mono = true)
            }

            Spacer(Modifier.height(16.dp))
            InfoCard(title = "媒体库") {
                if (state.snapshot.isStub) {
                    Text(
                        text = "数据层尚未接入 MediaStore，将在 M1 接入后显示真实数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                InfoRow("图片", if (state.snapshot.isStub) "—" else "${state.snapshot.imageCount} 张")
                InfoRow("视频", if (state.snapshot.isStub) "—" else "${state.snapshot.videoCount} 个")
                InfoRow("占用空间", state.totalSizeLabel)
            }

            Spacer(Modifier.height(16.dp))
            InfoCard(title = "开发里程碑") {
                milestones.forEachIndexed { index, milestone ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    MilestoneRow(milestone)
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "M0 验证目标：多模块构建 · 依赖注入 · 签名打包 · GitHub Release 自动发布",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    mono: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = if (mono) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
        )
    }
}

@Composable
private fun MilestoneRow(milestone: Milestone) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = CircleShape,
            color = milestone.status.color,
        ) {}
        Spacer(Modifier.size(10.dp))
        Text(
            text = milestone.id,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = milestone.title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = milestone.status.label,
            style = MaterialTheme.typography.labelSmall,
            color = milestone.status.color,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BootstrapScreenPreview() {
    SwipeGalleryTheme(dynamicColor = false) {
        BootstrapScreen(state = BootstrapUiState(versionName = "0.1.0", versionCode = 1, buildType = "debug"))
    }
}
