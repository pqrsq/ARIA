package com.example.aria.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * The single Compose theme for ARIA. Screens never hardcode colours; they read
 * them from MaterialTheme.colorScheme so a future light theme is a one-file job.
 */
private val AriaDarkScheme = darkColorScheme(
    primary = AriaColors.Accent,
    onPrimary = AriaColors.Background,
    primaryContainer = AriaColors.AccentSoft,
    onPrimaryContainer = AriaColors.TextPrimary,
    background = AriaColors.Background,
    onBackground = AriaColors.TextPrimary,
    surface = AriaColors.Surface,
    onSurface = AriaColors.TextPrimary,
    surfaceVariant = AriaColors.SurfaceHighlight,
    onSurfaceVariant = AriaColors.TextSecondary,
    error = AriaColors.Error,
    outline = AriaColors.TextTertiary
)

@Composable
fun AriaTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Version 1 is dark-only by design (see Color.kt).
    MaterialTheme(
        colorScheme = AriaDarkScheme,
        typography = AriaTypography,
        content = content
    )
}
