package dev.sonora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import dev.sonora.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.sonora.backend.LibraryTrack
import dev.sonora.ui.theme.accentText

/**
 * The play and skip glyph slots, and the bar's height.
 *
 * 48 rather than 40 because these are the three most-tapped controls in the app and Material's
 * minimum target is 48dp. The slot is now taller than the 40dp artwork opposite it, so it is the
 * slot that sets the bar's height; the glyphs stay the size they were inside it.
 */
private val GLYPH_SLOT = 48.dp

private val PLAY_GLYPH_SIZE = 36.dp
private val SKIP_GLYPH_SIZE = 22.dp

/** The spinner that stands in for the play glyph, kept in proportion to it. */
private val SPINNER_SIZE = 22.dp

/**
 * The gap between the two transport controls.
 *
 * Material asks for at least 8dp between adjacent touch targets, and these would otherwise share an
 * edge, so the boundary between "pause" and "skip" is a line with nothing either side of it. What
 * space there looked to be was only the margin each glyph keeps inside its own slot, and a thumb
 * lands on a target's edge far more often than it lands on a glyph's.
 *
 * Taken from the title's width rather than the bar's height, so nothing above or below it moves.
 */
private val TRANSPORT_GAP = 8.dp

/**
 * Vertical padding, which with the 48dp glyph slot sets the bar's height at 64dp and so its pill
 * radius at 32.
 */
private val ROW_PADDING_VERTICAL = 8.dp

/**
 * Horizontal padding, deliberately larger than the vertical.
 *
 * A pill's ends are semicircles, so the edge nearest the artwork is not the one beside it but the one
 * curving away above and below it. At the artwork's top corner that edge has already come 8.4dp in
 * from the left — level with where square corners would have put the whole side. Padding the ends by
 * the vertical figure would leave the artwork touching the curve; 12 clears it with room, and reads
 * as centred rather than jammed into the round.
 */
private val ROW_PADDING_HORIZONTAL = 12.dp

/** The artwork's corner, on the same 8dp every other thumbnail in the app carries. */
private val ART_CORNER = 8.dp

/** Distance that makes a horizontal drag an intentional track change. */
private val TRACK_SWIPE_THRESHOLD = 72.dp

/**
 * A left swipe advances through the queue, a right swipe goes back.
 *
 * Waiting until drag end prevents one long gesture from skipping more than one item. Playback state
 * updates can recompose the bar while a finger is down, so the gesture coroutine is kept alive
 * through those updates while still dispatching to the latest callbacks when the drag finishes.
 */
@Composable
private fun Modifier.miniPlayerTrackSwipe(
    onNext: () -> Unit,
    onPrevious: () -> Unit,
): Modifier {
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)

    return pointerInput(Unit) {
        val threshold = TRACK_SWIPE_THRESHOLD.toPx()
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onDragCancel = { totalDrag = 0f },
            onDragEnd = {
                when {
                    totalDrag <= -threshold -> currentOnNext()
                    totalDrag >= threshold -> currentOnPrevious()
                }
                totalDrag = 0f
            },
            onHorizontalDrag = { change, amount ->
                change.consume()
                totalDrag += amount
            },
        )
    }
}

/**
 * The frosted pill that rides just above the tab bar, showing what is playing.
 *
 * A pill rather than a full-width bar so it lines up with the tab bar's own ends: two bars of the
 * same width at the same inset read as one structure, where a full-width bar above an inset pill
 * reads as two things that were not drawn together.
 *
 * The whole bar is the tap target for opening the player, so a stray tap meant for the page behind
 * it is swallowed rather than opening the player by accident. The glyphs on it still do their own
 * thing.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun MiniPlayer(
    track: LibraryTrack,
    isPlaying: Boolean,
    /** True while the stream for this track is still being fetched. */
    isResolving: Boolean = false,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER)
            .clip(shape)
            .optimizedHazeEffect(
                state = hazeState,
                style = HazeMaterials.thin(MaterialTheme.colorScheme.surface),
            )
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
            .clickable(onClick = onExpand)
            .miniPlayerTrackSwipe(onNext = onNext, onPrevious = onPrevious),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ROW_PADDING_HORIZONTAL,
                    vertical = ROW_PADDING_VERTICAL,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(ART_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val artwork = rememberTrackArtwork(track, px = ROW_ART_PX)
                if (artwork != null) {
                    Image(
                        bitmap = artwork,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!track.artist.isNullOrBlank()) {
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(TRANSPORT_GAP))

            TransportButton(onClick = onPrevious) {
                Icon(
                    painter = painterResource(R.drawable.ic_player_previous),
                    contentDescription = "Previous",
                    modifier = Modifier.size(SKIP_GLYPH_SIZE),
                )
            }

            // The spinner takes the play button's own slot rather than appearing beside it, so the
            // bar does not change width when a track starts resolving, and so "busy" replaces
            // "play" rather than adding a second thing to read.
            if (isResolving) {
                Box(Modifier.size(GLYPH_SLOT), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(SPINNER_SIZE),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.accentText,
                    )
                }
            } else {
                TransportButton(
                    onClick = onPlayPause,
                    tint = MaterialTheme.colorScheme.accentText,
                ) {
                    Icon(
                        painter = painterResource(if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play),
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(PLAY_GLYPH_SIZE),
                    )
                }
            }

            TransportButton(onClick = onNext) {
                Icon(
                    painter = painterResource(R.drawable.ic_player_next),
                    contentDescription = "Next",
                    modifier = Modifier.size(SKIP_GLYPH_SIZE),
                )
            }
        }
    }
}

@Composable
private fun TransportButton(
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onBackground,
    glyph: @Composable () -> Unit,
) {
    var isPressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.84f else 1f,
        animationSpec = spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessHigh,
        ),
        label = "miniTransportPress",
    )
    val currentOnClick by rememberUpdatedState(onClick)

    Box(
        modifier = Modifier
            .size(GLYPH_SLOT)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    val up = waitForUpOrCancellation()
                    isPressed = false
                    if (up != null) {
                        currentOnClick()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides tint) {
                glyph()
            }
        }
    }
}
