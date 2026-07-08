package com.chrono.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A deliberate instrument-panel palette: near-black blue, amber primary,
// teal accent. The app is always dark — it reads like a piece of test gear.
val Ink = Color(0xFF0B0F14)
val Panel = Color(0xFF141B24)
val PanelHigh = Color(0xFF1C2530)
val Amber = Color(0xFFF5A524)
val Teal = Color(0xFF2DD4BF)
val TextPrimary = Color(0xFFE8EDF2)
val TextDim = Color(0xFF8A97A5)
val Good = Color(0xFF4ADE80)
val Bad = Color(0xFFF87171)
val ClipRed = Color(0xFFDC2626)
val ClipBlack = Color(0xFF11151A)

private val ChronoColors = darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    secondary = Teal,
    onSecondary = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = TextDim,
    error = Bad,
    outline = Color(0xFF2A3542),
)

private val ChronoType = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 56.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 29.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(fontSize = 19.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.sp,
    ),
)

@Composable
fun ChronoTheme(content: @Composable () -> Unit) {
    isSystemInDarkTheme() // always dark by design
    MaterialTheme(
        colorScheme = ChronoColors,
        typography = ChronoType,
        content = content,
    )
}
