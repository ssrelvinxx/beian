package com.beian.tracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 花花动态：粉嫩可爱风配色。 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFFF6B9D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E8),
    onPrimaryContainer = Color(0xFF7A2C48),
    secondary = Color(0xFFFFA8C5),
    onSecondary = Color(0xFF5A2033),
    secondaryContainer = Color(0xFFFFE4EF),
    onSecondaryContainer = Color(0xFF6B3049),
    tertiary = Color(0xFFFFC46B),
    onTertiary = Color(0xFF4A3000),
    background = Color(0xFFFFF7FA),
    onBackground = Color(0xFF3D2C34),
    surface = Color(0xFFFFFBFD),
    onSurface = Color(0xFF3D2C34),
    surfaceVariant = Color(0xFFFFEAF1),
    onSurfaceVariant = Color(0xFF7A6068),
    outline = Color(0xFFD9BCC7),
    outlineVariant = Color(0xFFF0D8E1),
    error = Color(0xFFE5484D),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF9EBE),
    onPrimary = Color(0xFF5A1B33),
    primaryContainer = Color(0xFF7A2C48),
    onPrimaryContainer = Color(0xFFFFD9E8),
    secondary = Color(0xFFE8A0B8),
    onSecondary = Color(0xFF4A2030),
    secondaryContainer = Color(0xFF5E2E40),
    onSecondaryContainer = Color(0xFFFFE4EF),
    tertiary = Color(0xFFE8C08A),
    onTertiary = Color(0xFF3A2800),
    background = Color(0xFF241A1E),
    onBackground = Color(0xFFF5E2E9),
    surface = Color(0xFF2B1F24),
    onSurface = Color(0xFFF5E2E9),
    surfaceVariant = Color(0xFF3D2C34),
    onSurfaceVariant = Color(0xFFD4BCC5),
    outline = Color(0xFF8A707A),
    outlineVariant = Color(0xFF4A3A42),
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
