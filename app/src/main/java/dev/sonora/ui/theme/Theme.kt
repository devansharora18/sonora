package dev.sonora.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The dark scheme is the design; the light one exists only so the app is not unreadable if the
 * platform forces it.
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

/**
 * A minimal light counterpart. Not designed — just legible, because a half-tuned second palette
 * is worse than an obviously provisional one.
 */
private val LightScheme = lightColorScheme(
    primary = SonoraColors.Accent,
    onPrimary = SonoraColors.OnAccent,
)

/**
 * Dark by default, regardless of the system setting.
 *
 * This is a deliberate design decision rather than an oversight: the target look is a dark music
 * app, and following the system would give a light UI that has not been designed. The light scheme
 * exists so nothing is unreadable if it is ever selected, and a proper light mode is a token remap
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
