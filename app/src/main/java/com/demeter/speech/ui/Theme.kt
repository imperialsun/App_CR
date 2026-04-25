package com.demeter.speech.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF111827),
    onPrimary = Color.White,
    secondary = Color(0xFF2563EB),
    background = Color(0xFFF7F7F5),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8E7E2),
    onSurface = Color(0xFF171717),
    outline = Color(0xFFD1D5DB),
    error = Color(0xFFB42318),
)

@Composable
fun DemeterSpeechTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
