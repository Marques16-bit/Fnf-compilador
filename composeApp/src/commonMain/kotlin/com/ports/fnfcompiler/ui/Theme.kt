package com.ports.fnfcompiler.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC56CF0),
    onPrimary = Color(0xFF1B0F2B),
    secondary = Color(0xFF3FD0EE),
    onSecondary = Color(0xFF06222A),
    background = Color(0xFF171126),
    onBackground = Color(0xFFF0EBFB),
    surface = Color(0xFF221A38),
    onSurface = Color(0xFFF0EBFB),
    surfaceVariant = Color(0xFF120D20),
    onSurfaceVariant = Color(0xFFA79FC4),
    outline = Color(0xFF3A2F5A),
    error = Color(0xFFFF5C78)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFA24BD6),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF12A8C9),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF3F0FA),
    onBackground = Color(0xFF241B3A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF241B3A),
    surfaceVariant = Color(0xFFEDE8F8),
    onSurfaceVariant = Color(0xFF6B6485),
    outline = Color(0xFFD9D2EC),
    error = Color(0xFFE0344F)
)

val OkColor = Color(0xFF1FA85A)
val WarnColor = Color(0xFFE0344F)

@Composable
fun FnfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content
    )
}
