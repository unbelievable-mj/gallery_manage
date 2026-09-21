package com.haoli.swipegallery.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 品牌主色取自「删除/保留」这对动作：主色偏珊瑚红，强调色偏青绿
private val Coral = Color(0xFFD85A30)
private val CoralDark = Color(0xFFF0997B)
private val Teal = Color(0xFF0F6E56)
private val TealDark = Color(0xFF5DCAA5)

/** 删除动作的语义色，滑出卡片时使用 */
val DeleteAccent = Color(0xFFE24B4A)

/** 保留动作的语义色，滑出卡片时使用 */
val KeepAccent = Color(0xFF639922)

private val LightColors = lightColorScheme(
    primary = Coral,
    secondary = Teal,
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
)

private val DarkColors = darkColorScheme(
    primary = CoralDark,
    secondary = TealDark,
    background = Color(0xFF1C1B1F),
    surface = Color(0xFF1C1B1F),
)

/**
 * 应用统一主题。
 *
 * [dynamicColor] 在 Android 12 (API 31) 及以上可用，会跟随用户壁纸取色；
 * 低于该版本时自动回退到品牌配色。
 */
@Composable
fun SwipeGalleryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
