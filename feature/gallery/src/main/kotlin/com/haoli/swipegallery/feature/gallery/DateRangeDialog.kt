package com.haoli.swipegallery.feature.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 日期范围选择。
 *
 * 形态参考订酒店那类日历：一次选定「起—止」两端，中间高亮。
 * 用 Material3 的 [DateRangePicker]，它内部取的是 `MaterialTheme.colorScheme`，
 * 因此外观会跟随应用主题，浅色 / 深色都一致，不需要另做一套样式。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeDialog(
    current: com.haoli.swipegallery.core.model.DateRange?,
    onDismiss: () -> Unit,
    onConfirm: (com.haoli.swipegallery.core.model.DateRange) -> Unit,
    onClear: () -> Unit,
) {
    val state = rememberDateRangePickerState(
        // 打开时定位到当月 —— 与「今天」对齐，绝大多数筛选都是近期照片
        initialDisplayedMonthMillis = todayUtcMidnightMillis(),
        initialSelectedStartDateMillis = current?.startMillis?.toUtcMidnightMillis(),
        initialSelectedEndDateMillis = current?.endMillis?.toUtcMidnightMillis(),
    )

    Dialog(
        onDismissRequest = onDismiss,
        // 日历比默认对话框宽，用平台默认宽度会把它压变形
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Column(modifier = Modifier.padding(top = 20.dp, bottom = 12.dp)) {
                Text(
                    text = "按日期筛选",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )

                DateRangePicker(
                    state = state,
                    // 关掉「键盘输入」切换：这里只有日历一种输入方式，
                    // 多一个切换按钮只会让人犹豫
                    showModeToggle = false,
                    // 用自己的标题，所以把组件自带的标题关掉，避免两层标题叠在一起。
                    // headline（选中区间回显）保留默认实现 —— 那是这个组件的核心信息。
                    title = null,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(text = "清除", onClick = onClear)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(text = "取消", onClick = onDismiss)
                        TextButton(
                            text = "确定",
                            enabled = state.selectedStartDateMillis != null,
                            onClick = {
                                val start = state.selectedStartDateMillis
                                val end = state.selectedEndDateMillis ?: start
                                if (start != null && end != null) {
                                    onConfirm(dayRangeOf(start, end))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        },
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

/** 今天（设备时区）对应的 UTC 零点毫秒 —— 日历组件内部按 UTC 理解日期。 */
private fun todayUtcMidnightMillis(): Long =
    LocalDate.now(ZoneId.systemDefault())
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

/** 把设备时区的某一天换算成 UTC 零点毫秒，供日历回显选中态。 */
private fun Long.toUtcMidnightMillis(): Long =
    Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

/**
 * 把日历给出的两天（UTC 零点）转成设备时区的**整天**区间。
 *
 * 结束端取当天 23:59:59.999 而不是零点：用户选 9/1 到 9/3，
 * 期望的是包含 9/3 一整天，取零点会把 9/3 拍的照片全部漏掉。
 */
private fun dayRangeOf(
    startUtcMillis: Long,
    endUtcMillis: Long,
): com.haoli.swipegallery.core.model.DateRange {
    val zone = ZoneId.systemDefault()
    fun toLocalDate(utc: Long): LocalDate =
        Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()

    val from = toLocalDate(startUtcMillis).atStartOfDay(zone).toInstant().toEpochMilli()
    val to = toLocalDate(endUtcMillis)
        .plusDays(1)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli() - 1L

    return com.haoli.swipegallery.core.model.DateRange(from, to)
}
