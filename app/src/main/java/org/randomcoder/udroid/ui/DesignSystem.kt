package org.randomcoder.udroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Management surfaces: quiet Android neutrals with one operational green.
val UdroidCanvas = Color(0xFFF2F4F3)
val UdroidSurface = Color(0xFFF9FAF9)
val UdroidRaised = Color(0xFFFFFFFF)
val UdroidInset = Color(0xFFEAEEEC)
val UdroidInk = Color(0xFF171C19)
val UdroidMuted = Color(0xFF626B66)
val UdroidFaint = Color(0xFF8B938F)
val UdroidForest = Color(0xFF176B4A)
val UdroidSoftGreen = Color(0xFFDDEEE6)
val UdroidLine = Color(0xFFD9DEDB)
val UdroidStrongLine = Color(0xFFC6CDC9)
val UdroidUbuntu = Color(0xFFE95420)
val UdroidWarm = Color(0xFFFFE9DF)
val UdroidWarning = Color(0xFF8A5B00)
val UdroidWarningSurface = Color(0xFFFFE9B8)

// Terminal workspace: the management shell gives way to a focused instrument.
val UdroidTerminal = Color(0xFF11131F)
val UdroidTerminalSurface = Color(0xFF181B2A)
val UdroidTerminalRaised = Color(0xFF222638)
val UdroidTerminalLine = Color(0xFF2C3145)
val UdroidTerminalText = Color(0xFFE3E7EF)
val UdroidTerminalMuted = Color(0xFF9AA2B5)
val UdroidTerminalGreen = Color(0xFF43D292)

object UdroidSpacing {
    val unit = 4
    val compact = 8
    val control = 12
    val content = 16
    val section = 24
}

private val UdroidTypography =
    Typography(
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 25.sp,
                lineHeight = 30.sp,
                letterSpacing = (-0.35).sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 21.sp,
                lineHeight = 26.sp,
                letterSpacing = (-0.2).sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                lineHeight = 23.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                lineHeight = 20.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 23.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                letterSpacing = 0.25.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            ),
    )

@Composable
fun UdroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = UdroidForest,
                onPrimary = Color.White,
                primaryContainer = UdroidSoftGreen,
                onPrimaryContainer = UdroidInk,
                secondaryContainer = UdroidInset,
                onSecondaryContainer = UdroidInk,
                surface = UdroidSurface,
                surfaceVariant = UdroidInset,
                onSurface = UdroidInk,
                onSurfaceVariant = UdroidMuted,
                background = UdroidCanvas,
                onBackground = UdroidInk,
                outline = UdroidStrongLine,
                outlineVariant = UdroidLine,
                error = Color(0xFFB3261E),
                errorContainer = Color(0xFFF9DEDC),
            ),
        typography = UdroidTypography,
        content = content,
    )
}

@Composable
fun UdroidTerminalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = UdroidTerminalGreen,
                onPrimary = Color(0xFF052116),
                primaryContainer = Color(0xFF164A39),
                onPrimaryContainer = UdroidTerminalText,
                surface = UdroidTerminalSurface,
                surfaceVariant = UdroidTerminalRaised,
                onSurface = UdroidTerminalText,
                onSurfaceVariant = UdroidTerminalMuted,
                background = UdroidTerminal,
                onBackground = UdroidTerminalText,
                outline = UdroidTerminalLine,
                error = Color(0xFFFFB4AB),
            ),
        typography = UdroidTypography,
        content = content,
    )
}
