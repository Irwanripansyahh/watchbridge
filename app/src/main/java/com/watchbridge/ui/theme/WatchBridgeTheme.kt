package com.watchbridge.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.Typography

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

private val WatchBridgeTypography = Typography(
    title1 = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        letterSpacing = 0.sp
    ),
    title2 = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = 0.sp
    ),
    title3 = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.sp
    ),
    body1 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        letterSpacing = 0.25.sp
    ),
    body2 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.25.sp
    ),
    caption1 = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp
    ),
    caption2 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp
    ),
    caption3 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        letterSpacing = 0.4.sp
    )
)

// Semantic extension colors
val StatusConnected = Color(0xFF4FC3F7)
val StatusDisconnected = Color(0xFFEF5350)
val StatusConnecting = Color(0xFFFFD54F)
val StatusIdle = Color(0xFF757575)
val SurfaceCard = Color(0xFF22223A)

@Composable
fun WatchBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = WatchBridgeColors,
        typography = WatchBridgeTypography,
        content = content
    )
}
