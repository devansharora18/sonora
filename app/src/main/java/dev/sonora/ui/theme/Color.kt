package dev.sonora.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette tokens.
 *
 * Deliberately dark-only. The target look is a dark music app, and one scheme done properly beats
 * two done halfway — a light mode is a token remap to add later, not a second design.
 *
 * Accent is used sparingly: playback state and primary actions only. Everything else is neutral,
 * which is what makes the accent read as emphasis rather than decoration.
 */
internal object SonoraColors {

    /** Near-black, not pure black: pure black makes elevation impossible to show. */
    val Background = Color(0xFF030303)

    /** Cards and raised rows. */
    val Surface = Color(0xFF121212)

    /** Menus, dialogs, the mini player. */
    val SurfaceElevated = Color(0xFF212121)

    val OnBackground = Color(0xFFFFFFFF)

    /** Secondary text: artist names, counts, durations. */
    val OnSurfaceMuted = Color(0xFFAAAAAA)

    val Outline = Color(0xFF303030)

    val Accent = Color(0xFFFF0033)

    val OnAccent = Color(0xFFFFFFFF)

    val Error = Color(0xFFFF8A80)
}
