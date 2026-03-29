package com.conuhacks.blearduinov1.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val VesperColors = darkColorScheme(
    background = Color(0xFF0D0D0D),
    surface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFF242424),
    primary = Color(0xFFFF3B3B),
    secondary = Color(0xFF4ECDC4),
    tertiary = Color(0xFFFFE66D),
    onBackground = Color(0xFFF5F5F5),
    onSurface = Color(0xFFB0B0B0),
    onSurfaceVariant = Color(0xFF666666),
    onPrimary = Color(0xFFF5F5F5),
    onSecondary = Color(0xFF0D0D0D),
    onTertiary = Color(0xFF0D0D0D),
)

private val VesperTypography = Typography(
    titleLarge = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
    ),
)

@Composable
fun VesperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VesperColors,
        typography = VesperTypography,
        content = content,
    )
}
