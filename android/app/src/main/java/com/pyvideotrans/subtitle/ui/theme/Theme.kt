package com.pyvideotrans.subtitle.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = PtAccent,
    onPrimary = Color.White,
    primaryContainer = PtProgress,
    onPrimaryContainer = PtText,
    secondary = PtAccentHover,
    onSecondary = Color.White,
    secondaryContainer = PtButton,
    onSecondaryContainer = PtText,
    tertiary = PtAccentBright,
    background = PtBackground,
    onBackground = PtText,
    surface = PtSurface,
    onSurface = PtText,
    surfaceVariant = PtButton,
    onSurfaceVariant = PtMuted,
    surfaceContainer = PtSurface,
    surfaceContainerHigh = PtButton,
    outline = PtBorder,
    outlineVariant = PtButtonHover,
    error = PtError,
    onError = Color.White,
    errorContainer = Color(0xFF4B2A2F),
    onErrorContainer = PtText,
)

private val AppTypography = Typography()

@Composable
fun SubtitleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content,
    )
}
