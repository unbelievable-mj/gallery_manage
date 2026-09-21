package com.haoli.swipegallery.core.common

import java.util.Locale

private const val BYTES_PER_KB = 1024.0
private const val BYTES_PER_MB = BYTES_PER_KB * 1024
private const val BYTES_PER_GB = BYTES_PER_MB * 1024

/**
 * 把字节数格式化成人类可读的字符串。
 *
 * 查看器底部信息栏和存储统计页都用它，保证两处显示一致。
 * 使用 [Locale.US] 是为了避免在部分语言环境下小数点变成逗号，
 * 导致同一份数据在不同设备上看起来不一样。
 */
fun formatFileSize(bytes: Long): String {
    require(bytes >= 0) { "文件大小不能为负数，实际收到：$bytes" }

    return when {
        bytes < BYTES_PER_KB.toLong() -> "$bytes B"
        bytes < BYTES_PER_MB.toLong() -> String.format(Locale.US, "%.1f KB", bytes / BYTES_PER_KB)
        bytes < BYTES_PER_GB.toLong() -> String.format(Locale.US, "%.1f MB", bytes / BYTES_PER_MB)
        else -> String.format(Locale.US, "%.2f GB", bytes / BYTES_PER_GB)
    }
}

/**
 * 把毫秒时长格式化成 `mm:ss` 或 `h:mm:ss`。
 * 用于视频角标和查看器信息栏。
 */
fun formatDuration(millis: Long): String {
    require(millis >= 0) { "时长不能为负数，实际收到：$millis" }

    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
