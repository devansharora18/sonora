package dev.sonora.ui.theme

import androidx.compose.ui.graphics.Color

internal object SonoraColors {

    val Background = Color(0xFF000000)
    val Surface = Color(0xFF121212)
    val SurfaceElevated = Color(0xFF1E1E1E)

    val OnBackground = Color(0xFFFFFFFF)

    val OnSurfaceMuted = Color(0xFFB3B3B3)
    val OnSurfaceFaint = Color(0xFF8A8A8A)
    val Outline = Color(0xFF535353)

    /**
     * Accent for filled surfaces — a button, a selected chip — where content sits on top of it.
     *
     * Deeper than [AccentText] because white has to stay readable on it: white reaches 4.77:1
     * here, and only 3.96:1 on the brighter red.
     */
    val Accent = Color(0xFFE6002E)

    /**
     * Accent for text, icons and bars drawn on a dark background.
     *
     * Brighter than [Accent] because the contrast requirement runs the other way: this red reaches
     * 5.30:1 on black, where the deeper one manages only 4.40:1. One red cannot be best at both,
     * which is why these are separate tokens.
     */
    val AccentText = Color(0xFFFF0033)

    /**
     * Content on the accent surface.
     *
     * White, which is why the accent is deepened instead of left at full brightness: white only
     * reaches 4.77:1 on this red, and falls to 3.96:1 on a brighter one — below the 4.5:1 minimum
     * for body text.
     */
    val OnAccent = Color(0xFFFFFFFF)

    /** Content on the light secondary and error surfaces, which need dark text. */
    val OnLight = Color(0xFF000000)

    val Error = Color(0xFFFF8A80)
}
