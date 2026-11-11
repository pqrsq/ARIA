package com.example.aria.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * ARIA colour palette.
 *
 * DECISION: the app is intentionally dark-only for Version 1.
 * A voice assistant is typically used as a quickly-opened utility; a single
 * well-tuned dark palette removes an entire category of visual bugs (and a
 * light/dark switch) while the focus stays on reliability. Adding a light
 * theme later only requires extending [AriaTheme], not touching screens.
 */
object AriaColors {
    val Background = Color(0xFF07080D)
    val Surface = Color(0xFF10131F)
    val SurfaceHighlight = Color(0xFF181D2E)

    val Accent = Color(0xFF6C8BFF)
    val AccentSoft = Color(0xFF2A3560)

    val TextPrimary = Color(0xFFECEFFA)
    val TextSecondary = Color(0xFF9BA3BF)
    val TextTertiary = Color(0xFF6B7392)

    val Success = Color(0xFF4CD6A0)
    val Warning = Color(0xFFFFC46B)
    val Error = Color(0xFFFF7A85)
    val Listening = Color(0xFFFF6FA5)
}
