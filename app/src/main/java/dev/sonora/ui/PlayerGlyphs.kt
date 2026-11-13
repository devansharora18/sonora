package dev.sonora.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sonora.backend.RepeatMode

/**
 * A tactile secondary icon button: bare glyph, no disc container, with spring push-down response
 * and smooth active/idle tinting.
 */
@Composable
internal fun SubIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    glyphSize: Dp = 22.dp,
    activeTint: Color = Color.White,
    idleTint: Color = Color.White.copy(alpha = 0.55f),
) {
    var isPressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.86f else 1f,
        animationSpec = spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessHigh,
        ),
        label = "subButtonPress",
    )
    val tint by animateColorAsState(
        targetValue = if (active) activeTint else idleTint,
        animationSpec = tween(150),
        label = "subButtonTint",
    )
    val currentOnClick by rememberUpdatedState(onClick)

    Box(
        modifier = modifier
            .size(size)
            .semantics {
                role = Role.Button
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { currentOnClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(glyphSize)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The like control: bare heart icon that fills and turns bright white when liked. */
@Composable
internal fun LikeGlyph(
    liked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    glyphSize: Dp = 22.dp,
) {
    SubIconButton(
        icon = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        contentDescription = if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
        onClick = onClick,
        active = liked,
        activeTint = Color.White,
        modifier = modifier,
        size = size,
        glyphSize = glyphSize,
    )
}

/** The three-dot control that opens the track's own sheet. */
@Composable
internal fun MenuGlyph(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    glyphSize: Dp = 22.dp,
) {
    SubIconButton(
        icon = Icons.Rounded.MoreHoriz,
        contentDescription = "More",
        onClick = onClick,
        modifier = modifier,
        size = size,
        glyphSize = glyphSize,
    )
}

/** Backwards-compatible glyph for lists/panels. */
@Composable
internal fun CircleGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    glyphSize: Dp = 22.dp,
    active: Boolean = false,
    tint: Color? = null,
    disc: Boolean = false,
) {
    if (disc) {
        val fill by animateColorAsState(
            targetValue = if (active) Color.White.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.18f),
            animationSpec = tween(180),
            label = "glyphDisc",
        )
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(fill),
            contentAlignment = Alignment.Center,
        ) {
            SubIconButton(
                icon = icon,
                contentDescription = contentDescription,
                onClick = onClick,
                active = active,
                size = size,
                glyphSize = glyphSize,
                activeTint = tint ?: Color.White,
                idleTint = tint ?: Color.White,
            )
        }
    } else {
        SubIconButton(
            icon = icon,
            contentDescription = contentDescription,
            onClick = onClick,
            modifier = modifier,
            active = active,
            size = size,
            glyphSize = glyphSize,
            activeTint = tint ?: Color.White,
            idleTint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Clean, balanced row of secondary playback controls under the volume slider:
 * [Lyrics]   [Shuffle]   [Repeat]   [Queue]
 *
 * Centered with shuffle and replay in the middle, matching the transport row's visual span.
 */
@Composable
internal fun PlayerActionRow(
    lyricsOpen: Boolean,
    onToggleLyrics: () -> Unit,
    isShuffled: Boolean,
    onToggleShuffle: () -> Unit,
    repeatMode: RepeatMode,
    onCycleRepeat: () -> Unit,
    queueOpen: Boolean,
    onToggleQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubIconButton(
            icon = Icons.Rounded.Lyrics,
            contentDescription = if (lyricsOpen) "Hide lyrics" else "Show lyrics",
            active = lyricsOpen,
            onClick = onToggleLyrics,
        )

        SubIconButton(
            icon = Icons.Rounded.Shuffle,
            contentDescription = if (isShuffled) "Turn shuffle off" else "Turn shuffle on",
            active = isShuffled,
            onClick = onToggleShuffle,
        )

        SubIconButton(
            icon = when (repeatMode) {
                RepeatMode.One -> Icons.Rounded.RepeatOne
                else -> Icons.Rounded.Repeat
            },
            contentDescription = when (repeatMode) {
                RepeatMode.Off -> "Turn repeat on"
                RepeatMode.All -> "Turn repeat-one on"
                RepeatMode.One -> "Turn repeat off"
            },
            active = repeatMode != RepeatMode.Off,
            onClick = onCycleRepeat,
        )

        SubIconButton(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            contentDescription = if (queueOpen) "Close queue" else "Show queue",
            active = queueOpen,
            onClick = onToggleQueue,
        )
    }
}

/**
 * What to show instead of the sleeve when a track could not be fetched.
 *
 * A reason, a way to try again and a way on. All three, because the three things a listener can
 * think are "try again", "skip it" and "why", and a screen that gives only one of them answers
 * none of the others. Kept inside the sleeve's own square so nothing below it has to move.
 */
@Composable
internal fun UnplayableState(
    track: dev.sonora.backend.LibraryTrack,
    reason: String,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 4,
            )

            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onSkip,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White.copy(alpha = 0.8f),
                    ),
                ) {
                    Text("Skip")
                }
                Button(
                    onClick = onRetry,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                ) {
                    Text("Try again")
                }
            }
        }
    }
}
