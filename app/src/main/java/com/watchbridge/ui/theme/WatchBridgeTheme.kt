package com.watchbridge.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Colors

private val WatchBridgeColors = Colors(
    primary = Color(0xFF4FC3F7),
    primaryVariant = Color(0xFF0288D1),
    secondary = Color(0xFF81C784),
    secondaryVariant = Color(0xFF388E3C),
    error = Color(0xFFEF5350),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onError = Color.Black,
    surface = Color(0xFF1A1A2E),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFB0B0B0),
    background = Color.Black,
    onBackground = Color.White
)

@Composable
fun WatchBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = WatchBridgeColors,
        content = content
    )
}
