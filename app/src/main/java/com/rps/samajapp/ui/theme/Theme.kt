package com.rps.samajapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val SamajColorScheme = lightColorScheme(
    primary = BrandRed,
    onPrimary = OnPrimaryWhite,
    primaryContainer = BrandRedLight,
    onPrimaryContainer = OnPrimaryWhite,
    secondary = BrandRedDark,
    onSecondary = OnPrimaryWhite,
    background = BackgroundLight,
    onBackground = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
    surface = SurfaceWhite,
    onSurface = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF49454F),
)

@Composable
fun SamajAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SamajColorScheme,
        typography = Typography,
        content = content
    )
}
