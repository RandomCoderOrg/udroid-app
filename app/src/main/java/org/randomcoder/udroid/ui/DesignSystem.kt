package org.randomcoder.udroid.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val UdroidLightColors =
    lightColorScheme(
        primary = Color(0xFF236A4C),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFAAF2CB),
        onPrimaryContainer = Color(0xFF005236),
        inversePrimary = Color(0xFF8FD5B0),
        secondary = Color(0xFF4D6356),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD0E8D8),
        onSecondaryContainer = Color(0xFF364B3F),
        tertiary = Color(0xFF3D6472),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFC0E9FA),
        onTertiaryContainer = Color(0xFF234C5A),
        background = Color(0xFFF5FBF4),
        onBackground = Color(0xFF171D19),
        surface = Color(0xFFF5FBF4),
        onSurface = Color(0xFF171D19),
        surfaceVariant = Color(0xFFDCE5DD),
        onSurfaceVariant = Color(0xFF404943),
        surfaceTint = Color(0xFF236A4C),
        inverseSurface = Color(0xFF2C322E),
        inverseOnSurface = Color(0xFFEDF2EC),
        surfaceDim = Color(0xFFD6DBD5),
        surfaceBright = Color(0xFFF5FBF4),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF0F5EF),
        surfaceContainer = Color(0xFFEAEFE9),
        surfaceContainerHigh = Color(0xFFE4EAE3),
        surfaceContainerHighest = Color(0xFFDEE4DE),
        outline = Color(0xFF707973),
        outlineVariant = Color(0xFFC0C9C1),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
    )

private val UdroidDarkColors =
    darkColorScheme(
        primary = Color(0xFF8FD5B0),
        onPrimary = Color(0xFF003824),
        primaryContainer = Color(0xFF005236),
        onPrimaryContainer = Color(0xFFAAF2CB),
        inversePrimary = Color(0xFF236A4C),
        secondary = Color(0xFFB4CCBC),
        onSecondary = Color(0xFF20352A),
        secondaryContainer = Color(0xFF364B3F),
        onSecondaryContainer = Color(0xFFD0E8D8),
        tertiary = Color(0xFFA4CDDE),
        onTertiary = Color(0xFF063543),
        tertiaryContainer = Color(0xFF234C5A),
        onTertiaryContainer = Color(0xFFC0E9FA),
        background = Color(0xFF0F1511),
        onBackground = Color(0xFFDEE4DE),
        surface = Color(0xFF0F1511),
        onSurface = Color(0xFFDEE4DE),
        surfaceVariant = Color(0xFF404943),
        onSurfaceVariant = Color(0xFFC0C9C1),
        surfaceTint = Color(0xFF8FD5B0),
        inverseSurface = Color(0xFFDEE4DE),
        inverseOnSurface = Color(0xFF2C322E),
        surfaceDim = Color(0xFF0F1511),
        surfaceBright = Color(0xFF353B36),
        surfaceContainerLowest = Color(0xFF0A0F0C),
        surfaceContainerLow = Color(0xFF171D19),
        surfaceContainer = Color(0xFF1B211D),
        surfaceContainerHigh = Color(0xFF262B27),
        surfaceContainerHighest = Color(0xFF303632),
        outline = Color(0xFF8A938C),
        outlineVariant = Color(0xFF404943),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    )

// Existing call sites now consume Material roles instead of fixed light-only colors.
val UdroidCanvas = Color(0xFFF5FBF4)
val UdroidDarkCanvas = Color(0xFF0F1511)
val UdroidSurface: Color
    @Composable get() = MaterialTheme.colorScheme.surface
val UdroidRaised: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
val UdroidInset: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest
val UdroidInk: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
val UdroidMuted: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val UdroidFaint: Color
    @Composable get() = MaterialTheme.colorScheme.outline
val UdroidForest: Color
    @Composable get() = MaterialTheme.colorScheme.primary
val UdroidSoftGreen: Color
    @Composable get() = MaterialTheme.colorScheme.primaryContainer
val UdroidLine: Color
    @Composable get() = MaterialTheme.colorScheme.outlineVariant
val UdroidStrongLine: Color
    @Composable get() = MaterialTheme.colorScheme.outline

val UdroidUbuntu: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFFB59A) else Color(0xFF9D2B00)
val UdroidWarm: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF5C1900) else Color(0xFFFFDBCD)
val UdroidWarning: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFE9C349) else Color(0xFF6F5300)
val UdroidWarningSurface: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF3B2F00) else Color(0xFFFFE08B)

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

// Material 3 standard motion tokens. Use MotionScheme directly once it is public in stable M3.
internal object UdroidMotion {
    fun <T> defaultSpatial(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = 700f)

    fun <T> defaultEffects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)

    fun <T> fastEffects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)

    fun <T> slowEffects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 800f)
}

private val UdroidShapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

@Composable
fun UdroidTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colors =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
        } else if (darkTheme) {
            UdroidDarkColors
        } else {
            UdroidLightColors
        }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        shapes = UdroidShapes,
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
        typography = Typography(),
        shapes = UdroidShapes,
        content = content,
    )
}
