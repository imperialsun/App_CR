package com.demeter.speech.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp

private val DemeterLightColors = lightColorScheme(
    primary = Color(0xFF0B7A6B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD0F0EB),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF24404A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0EBEE),
    onSecondaryContainer = Color(0xFF041A22),
    tertiary = Color(0xFFC47A25),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE1BE),
    onTertiaryContainer = Color(0xFF301E00),
    background = Color(0xFFF7FAFB),
    onBackground = Color(0xFF102027),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF102027),
    surfaceVariant = Color(0xFFE5EEF0),
    onSurfaceVariant = Color(0xFF42545B),
    outline = Color(0xFF74878E),
)

private val DemeterDarkColors = darkColorScheme(
    primary = Color(0xFF87E0D4),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF00564E),
    onPrimaryContainer = Color(0xFFBCE7E2),
    secondary = Color(0xFFB4CAD2),
    onSecondary = Color(0xFF14313D),
    secondaryContainer = Color(0xFF294550),
    onSecondaryContainer = Color(0xFFD6E4EB),
    tertiary = Color(0xFFF2BC7A),
    onTertiary = Color(0xFF4B2700),
    tertiaryContainer = Color(0xFF6B3D00),
    onTertiaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFF071317),
    onBackground = Color(0xFFD6E4EB),
    surface = Color(0xFF0E1D23),
    onSurface = Color(0xFFE5EFF2),
    surfaceVariant = Color(0xFF20353D),
    onSurfaceVariant = Color(0xFFB7C7CE),
    outline = Color(0xFF7D9098),
)

private val DemeterTypography = Typography()

private val DemeterShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun DemeterSpeechTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DemeterDarkColors else DemeterLightColors,
        typography = DemeterTypography,
        shapes = DemeterShapes,
        content = content,
    )
}
