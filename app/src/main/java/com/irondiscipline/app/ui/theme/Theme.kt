package com.irondiscipline.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val IronDarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = DarkBg,
    primaryContainer = DarkCard,
    onPrimaryContainer = NeonCyan,
    secondary = NeonLime,
    onSecondary = DarkBg,
    tertiary = NeonAmber,
    onTertiary = DarkBg,
    error = NeonCrimson,
    onError = TextPrimary,
    background = DarkBg,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkCard,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder
)

@Composable
fun IronDisciplineTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = IronDarkColorScheme,
        typography = IronTypography,
        content = content
    )
}
