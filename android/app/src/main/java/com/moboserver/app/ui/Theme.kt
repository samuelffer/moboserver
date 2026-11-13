package com.moboserver.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object MoboColors {
    val Lime = Color(0xFF8BE04A)
    val LimeDark = Color(0xFF4E8F22)
    val LimeSoft = Color(0xFF2B451B)
    val Background = Color(0xFF0A0E09)
    val Surface = Color(0xFF131A11)
    val SurfaceHigh = Color(0xFF1B231A)
    val Outline = Color(0xFF2E3A2A)
    val TextPrimary = Color(0xFFE9EFE4)
    val TextSecondary = Color(0xFF9FAC99)
    val Danger = Color(0xFFFF6B6B)
    val Warning = Color(0xFFFFC24B)
    val Info = Color(0xFF62B6FF)
}

private val DarkScheme = darkColorScheme(
    primary = MoboColors.Lime,
    onPrimary = Color(0xFF10230A),
    primaryContainer = MoboColors.LimeSoft,
    onPrimaryContainer = Color(0xFFD9F7BC),
    secondary = Color(0xFF9CD67A),
    onSecondary = Color(0xFF10230A),
    secondaryContainer = Color(0xFF23331C),
    onSecondaryContainer = Color(0xFFD2E8C1),
    background = MoboColors.Background,
    onBackground = MoboColors.TextPrimary,
    surface = MoboColors.Surface,
    onSurface = MoboColors.TextPrimary,
    surfaceVariant = MoboColors.SurfaceHigh,
    onSurfaceVariant = MoboColors.TextSecondary,
    error = MoboColors.Danger,
    onError = Color(0xFF2A0909),
    outline = MoboColors.Outline,
)

@Composable
fun MoboTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = Typography(),
        content = content,
    )
}
