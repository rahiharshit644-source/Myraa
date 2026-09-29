package com.soltini.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkPolishColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = AccentIndigo,
    onPrimaryContainer = Color.White,
    secondary = AccentTeal,
    onSecondary = DarkBackground,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder,
    error = RedError,
    onError = Color.White
)

@Composable
fun GeminiVoiceTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkPolishColorScheme,
        typography = Typography,
        content = content
    )
}
