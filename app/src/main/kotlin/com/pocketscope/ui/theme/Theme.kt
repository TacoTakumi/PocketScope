package com.pocketscope.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NightVisionColorScheme = darkColorScheme(
    primary = Color(0xFFCC0000),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF4D0000),
    onPrimaryContainer = Color(0xFFFF6666),
    secondary = Color(0xFF993333),
    onSecondary = Color.Black,
    surface = Color(0xFF1A0000),
    onSurface = Color(0xFFCC0000),
    surfaceVariant = Color(0xFF2A0000),
    onSurfaceVariant = Color(0xFFAA3333),
    background = Color.Black,
    onBackground = Color(0xFFAA0000),
    error = Color(0xFFFF4444),
    onError = Color.Black,
    outline = Color(0xFF660000)
)

@Composable
fun PocketScopeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightVisionColorScheme,
        content = content
    )
}
