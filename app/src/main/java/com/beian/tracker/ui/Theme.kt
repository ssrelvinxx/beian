package com.beian.tracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF4A6FA5),
    onPrimary = Color.White,
    secondary = Color(0xFF6B8CAE),
    surfaceVariant = Color(0xFFE7ECF2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CB8DB),
    onPrimary = Color(0xFF10233A),
    secondary = Color(0xFF8FA6BF),
)

@Composable
fun BeiAnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}