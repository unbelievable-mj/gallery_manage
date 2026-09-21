package com.haoli.swipegallery

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Date

private const val CRASH_FILE_NAME = "last_crash.txt"

/**
 * 把未捕获异常的堆栈写进应用私有目录。
 *
 * 存在的理由很具体：这个项目没有可用的 adb，崩溃之后拿不到 logcat，
 * 只能靠用户口述「点一下退出了」—— 这种信息量不足以定位问题。
 * 写到文件后，下次启动会把堆栈直接显示在屏幕上，用户截个图就能带回来。
 *
 * 注意必须把异常继续交给上一个 handler，否则应用不会真正崩溃，
 * 系统会认为进程卡死，反而更难排查。
 */
fun installCrashLogger(context: Context) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()

    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching {
            File(context.filesDir, CRASH_FILE_NAME).writeText(
                buildString {
                    appendLine("时间: ${Date()}")
                    appendLine("线程: ${thread.name}")
                    appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine()
                    appendLine(Log.getStackTraceString(error))
                },
            )
        }
        previous?.uncaughtException(thread, error)
    }
}

fun readCrashReport(context: Context): String? =
    runCatching {
        File(context.filesDir, CRASH_FILE_NAME).takeIf { it.exists() }?.readText()
    }.getOrNull()?.takeIf { it.isNotBlank() }

fun clearCrashReport(context: Context) {
    runCatching { File(context.filesDir, CRASH_FILE_NAME).delete() }
}

/**
 * 崩溃回看页。
 *
 * 只在检测到上次异常退出时出现，展示堆栈并允许清除。
 * 用等宽字体是为了让缩进和行号对齐，截图后更容易读。
 */
@Composable
fun CrashScreen(report: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(20.dp),
    ) {
        Text(
            text = "上次运行崩溃了",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "把下面这段截图发给我就能定位问题。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                ),
        ) {
            Text(
                text = report,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }

        Spacer(Modifier.height(14.dp))

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDismiss),
        ) {
            Box(
                modifier = Modifier.padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "清除并继续",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
