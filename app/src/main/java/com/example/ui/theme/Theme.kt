package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM
}

private val CinemaDarkColorScheme = darkColorScheme(
    primary = ElectricCoral,
    onPrimary = OnElectricCoral,
    primaryContainer = ElectricCoralContainer,
    onPrimaryContainer = OnElectricCoralContainer,
    secondary = ElectricCyan,
    onSecondary = OnElectricCyan,
    secondaryContainer = ElectricCyanContainer,
    onSecondaryContainer = OnElectricCyanContainer,
    tertiary = WarmAmber,
    onTertiary = Color(0xFF2B1B00),
    tertiaryContainer = WarmAmberContainer,
    onTertiaryContainer = Color(0xFFFFE0B2),
    background = ObsidianBackground,
    onBackground = TextPrimaryDark,
    surface = SlateSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = ObsidianBackground,
    surfaceContainerLow = SlateSurface,
    surfaceContainer = SlateSurfaceVariant,
    surfaceContainerHigh = SlateSurfaceHigh,
    outline = SlateOutline,
    outlineVariant = Color(0xFF232B3D),
    error = ErrorCoral,
    onError = Color(0xFF3A0006),
    errorContainer = ErrorContainerDark,
    onErrorContainer = Color(0xFFFFDAD6)
)

private val CinemaLightColorScheme = lightColorScheme(
    primary = CoralPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = CoralContainerLight,
    onPrimaryContainer = OnCoralContainerLight,
    secondary = TealSecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = TealContainerLight,
    onSecondaryContainer = OnTealContainerLight,
    tertiary = Color(0xFFB36B00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE8C7),
    onTertiaryContainer = Color(0xFF351D00),
    background = AlabasterBackground,
    onBackground = TextPrimaryLight,
    surface = WhiteSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = SoftCoolSurface,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLowest = WhiteSurface,
    surfaceContainerLow = AlabasterBackground,
    surfaceContainer = SoftCoolSurface,
    surfaceContainerHigh = SoftCoolSurfaceHigh,
    outline = LightOutline,
    outlineVariant = Color(0xFFDCE3EE),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

val StreamVaultShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun StreamVaultTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (useDarkTheme) CinemaDarkColorScheme else CinemaLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = StreamVaultShapes,
        content = content
    )
}
