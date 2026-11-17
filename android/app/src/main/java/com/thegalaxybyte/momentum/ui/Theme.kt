package com.thegalaxybyte.momentum.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFF2BB673)
val Warn = Color(0xFFF2A93B)
val Danger = Color(0xFFE5534B)

private val Dark = darkColorScheme(
    primary = Brand,
    onPrimary = Color(0xFF00210F),
    primaryContainer = Color(0xFF0F4A2E),
    onPrimaryContainer = Color(0xFFB6F2CF),
    secondary = Color(0xFF8FD1B0),
    secondaryContainer = Color(0xFF1F4D38),
    onSecondaryContainer = Color(0xFFB6F2CF),
    surfaceVariant = Color(0xFF2A3239),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceContainer = Color(0xFF1A2026),
    surfaceContainerHigh = Color(0xFF222A31),
    surfaceContainerLow = Color(0xFF151A1F),
)

private val Light = lightColorScheme(
    primary = Color(0xFF13804F),
    primaryContainer = Color(0xFFC4EFD6),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF3D6A55),
    secondaryContainer = Color(0xFFC4EFD6),
    onSecondaryContainer = Color(0xFF00210F),
    surfaceVariant = Color(0xFFDDE8E1),
    background = Color(0xFFF7FAF8),
    surface = Color(0xFFF7FAF8),
    surfaceContainer = Color(0xFFEAF1EC),
    surfaceContainerHigh = Color(0xFFDDE8E1),
)

@Composable
fun MomentumTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
