package com.haoli.swipegallery.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 品牌主色取自「删除 / 保留」这对动作：主色偏珊瑚红，强调色偏青绿
private val Coral = Color(0xFFD85A30)
private val CoralLight = Color(0xFFFFDBCF)
private val CoralContainer = Color(0xFF7A2E14)
private val Teal = Color(0xFF0F6E56)
private val TealLight = Color(0xFFC8EAD9)
private val TealContainer = Color(0xFF005140)

/** 删除动作的语义色，滑出卡片时使用 */
val DeleteAccent = Color(0xFFE24B4A)

/** 保留动作的语义色，滑出卡片时使用 */
val KeepAccent = Color(0xFF639922)

/**
 * 浅色配色。
 *
 * 这里把用到的角色**逐个显式写出**，而不是只覆盖 primary/background 让其余走
 * M3 默认值 —— 默认值与自定义的品牌色未必协调，深色下容易出现
 * 「浅底浅字」这类看不清的组合。写全了对比度才是可控的。
 */
private val LightColors = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = CoralLight,
    onPrimaryContainer = Color(0xFF3A0B00),

    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = TealLight,
    onSecondaryContainer = Color(0xFF002115),

    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF2E3DD),
    onSurfaceVariant = Color(0xFF53433F),
    outline = Color(0xFF85736E),

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** 深色配色。每个角色都与浅色版一一对应，保证两套模式的语义一致。 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB59B),
    onPrimary = Color(0xFF561F0C),
    primaryContainer = CoralContainer,
    onPrimaryContainer = CoralLight,

    secondary = Color(0xFF8FD8BC),
    onSecondary = Color(0xFF003828),
    secondaryContainer = TealContainer,
    onSecondaryContainer = TealLight,

    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE8E1E3),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE8E1E3),
    surfaceVariant = Color(0xFF53433F),
    onSurfaceVariant = Color(0xFFD9C2BB),
    outline = Color(0xFFA08C87),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * 应用统一主题。
 *
 * [darkTheme] 由上层决定：跟随系统、强制浅色、强制深色三种都由调用方传入，
 * 这里只负责把模式映射成配色。
 *
 * **刻意不启用动态取色（dynamicColor）**：它跟随壁纸取色，对比度完全不可控 ——
 * 壁纸是什么色，界面就是什么色，很容易出现文字与背景区分不开的情况。
 * 本应用有明确的品牌配色，用固定色板换来稳定的可读性更划算。
 */
@Composable
fun SwipeGalleryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
