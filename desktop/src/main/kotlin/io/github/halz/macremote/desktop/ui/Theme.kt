package io.github.halz.macremote.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** App palette: one dark theme, matching the remote-screen chrome (same as Android). */
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

@Composable
fun IconTile(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.SurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = AppColors.Text, fontSize = 17.sp) }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) AppColors.Accent else AppColors.SurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Color.White else AppColors.Subtle, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

