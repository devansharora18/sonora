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

    val Accent = Color(0xFFE6002E)

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
