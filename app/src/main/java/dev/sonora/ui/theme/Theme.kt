package dev.sonora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The dark scheme is the design; the light one exists only so the app is not unreadable if the
 * platform ever forces it.
 */
private val DarkScheme = darkColorScheme(
    primary = SonoraColors.Accent,
    onPrimary = SonoraColors.OnAccent,
    background = SonoraColors.Background,
    onBackground = SonoraColors.OnBackground,
    surface = SonoraColors.Surface,
    onSurface = SonoraColors.OnBackground,
    surfaceVariant = SonoraColors.SurfaceElevated,
    onSurfaceVariant = SonoraColors.OnSurfaceMuted,
    outline = SonoraColors.Outline,
    error = SonoraColors.Error,
)

private val LightScheme = lightColorScheme(
    primary = SonoraColors.Accent,
    onPrimary = SonoraColors.OnAccent,
)

/**
 * Tertiary text: the least important line in a row.
 *
 * Material3 has no slot below `onSurfaceVariant`, so this derives one from the scheme's own
 * background rather than hardcoding a colour in each screen. Deliberately not `outline`, which is
 * a border colour and unreadable as text on a dark background.
 */
val ColorScheme.onSurfaceFaint: Color
    get() = if (background.luminance() > 0.5f) Color(0xFF606060) else SonoraColors.OnSurfaceFaint

/**
 * Dark by default, regardless of the system setting.
 *
 * A deliberate choice, not an oversight: the target look is a dark music app, and following the
 * system would serve a light UI that has not been designed. A proper light mode is a token remap
 * to do later.
 */
@Composable
fun SonoraTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
