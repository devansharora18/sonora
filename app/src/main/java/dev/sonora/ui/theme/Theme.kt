package dev.sonora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = SonoraColors.Accent,
    onPrimary = SonoraColors.OnAccent,
    secondary = SonoraColors.OnSurfaceMuted,
    onSecondary = SonoraColors.OnAccent,
    tertiary = SonoraColors.OnSurfaceMuted,
    onTertiary = SonoraColors.OnAccent,
    background = SonoraColors.Background,
    onBackground = SonoraColors.OnBackground,
    surface = SonoraColors.Surface,
    onSurface = SonoraColors.OnBackground,
    surfaceVariant = SonoraColors.SurfaceElevated,
    onSurfaceVariant = SonoraColors.OnSurfaceMuted,
    outline = SonoraColors.Outline,
    error = SonoraColors.Error,
    onError = SonoraColors.OnAccent,
)

val ColorScheme.onSurfaceFaint: Color
    get() = SonoraColors.OnSurfaceFaint

@Composable
fun SonoraTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkScheme,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = DarkScheme.background,
            contentColor = DarkScheme.onBackground,
            content = content,
        )
    }
}
