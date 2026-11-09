package io.github.halz.macremote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** App palette: one dark theme, matching the remote-screen chrome. */
object AppColors {
    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF171B22)
    val SurfaceHigh = Color(0xFF1F2530)
    val Outline = Color(0xFF2A3140)
    val Text = Color(0xFFE8ECF2)
    val Subtle = Color(0xFF8B95A7)
    val Accent = Color(0xFF4F8CFF)
    val Online = Color(0xFF38D27A)
    val Danger = Color(0xFFFF6B6B)
}

@Composable
fun MacRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AppColors.Accent,
            onPrimary = Color.White,
            primaryContainer = AppColors.Accent,
            onPrimaryContainer = Color.White,
            secondaryContainer = AppColors.SurfaceHigh,
            onSecondaryContainer = AppColors.Text,
            background = AppColors.Background,
            onBackground = AppColors.Text,
            surface = AppColors.Surface,
            onSurface = AppColors.Text,
            surfaceVariant = AppColors.SurfaceHigh,
            onSurfaceVariant = AppColors.Subtle,
            surfaceContainer = AppColors.Surface,
            surfaceContainerLow = AppColors.Surface,
            surfaceContainerHigh = AppColors.SurfaceHigh,
            surfaceContainerHighest = AppColors.SurfaceHigh,
            outline = AppColors.Outline,
            outlineVariant = AppColors.Outline,
            error = AppColors.Danger,
        ),
        content = content,
    )
}
