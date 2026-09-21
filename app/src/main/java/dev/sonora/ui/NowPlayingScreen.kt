package dev.sonora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.statusBarsPadding
import dev.sonora.backend.RepeatMode
import dev.sonora.backend.SonoraPlayer
import dev.sonora.ui.theme.accentText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onClose: () -> Unit,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    val playback by SonoraPlayer.state.collectAsState()
    val track = playback.track ?: return
    // Non-null only while a finger is down on the bar. Held locally so the polled position cannot
    // drag the handle back out from under the drag.
    var scrubbing by remember(track.file) { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs
    val played = scrubbing ?: if (duration > 0L) {
        (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close player")
            }
            Text(
                text = "Now Playing",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = onToggleLike) {
                Icon(
                    imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isLiked) {
                        "Remove from Liked Songs"
                    } else {
                        "Add to Liked Songs"
                    },
                    tint = if (isLiked) {
                        MaterialTheme.colorScheme.accentText
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onAddToPlaylist) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = "Add to playlist",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val artwork = rememberArtwork(track.file)
                if (artwork != null) {
                    Image(
                        bitmap = artwork,
                        contentDescription = "Album artwork",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(72.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(track.artist, track.album).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Slider(
                    value = played,
                    onValueChange = { scrubbing = it },
                    onValueChangeFinished = {
                        val target = scrubbing
                        scrubbing = null
                        if (target != null) {
                            SonoraPlayer.seekTo((target.toDouble() * duration).toLong())
                        }
                    },
                    // A track with no known duration cannot be seeked into.
                    enabled = duration > 0L,
                    // A dot rather than the Material default: the default thumb is a tall bar that
                    // reads as a rendering glitch against a track this thin. Touch target is
                    // unaffected — that comes from the slider's layout, not the thumb's size.
                    // Supplied rather than defaulted: the Material track draws a stop dot at the
                    // far end, which reads as a second handle on a track this thin.
                    track = { sliderState ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(sliderState.value)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.accentText),
                            )
                        }
                    },
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.accentText,
                                    shape = CircleShape,
                                ),
                        )
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.accentText,
                        activeTrackColor = MaterialTheme.colorScheme.accentText,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Shows where the finger is, not where playback has got to, so the numbers and
                    // the handle agree while scrubbing.
                    Text(
                        text = formatMillis((played.toDouble() * duration).toLong()),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = formatMillis(duration),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = if (playback.isShuffled) {
                            "Turn shuffle off"
                        } else {
                            "Turn shuffle on"
                        },
                        // Active state is carried by colour, since a shuffle icon has no filled
                        // counterpart to switch to.
                        tint = if (playback.isShuffled) {
                            MaterialTheme.colorScheme.accentText
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(
                    onClick = { SonoraPlayer.previous() },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(
                    onClick = { SonoraPlayer.togglePlayPause() },
                    modifier = Modifier
                        .size(88.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                ) {
                    Icon(
                        imageVector = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(42.dp),
                    )
                }
                IconButton(
                    onClick = { SonoraPlayer.next() },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(
                    onClick = onCycleRepeat,
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        // Repeat One is the only mode with a distinct glyph; off and loop-queue
                        // share one and are told apart by colour, as they are in Spotify.
                        imageVector = if (playback.repeatMode == RepeatMode.One) {
                            Icons.Filled.RepeatOne
                        } else {
                            Icons.Filled.Repeat
                        },
                        contentDescription = when (playback.repeatMode) {
                            RepeatMode.Off -> "Turn repeat on"
                            RepeatMode.All -> "Turn repeat-one on"
                            RepeatMode.One -> "Turn repeat off"
                        },
                        tint = if (playback.repeatMode == RepeatMode.Off) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.accentText
                        },
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

private fun formatMillis(value: Long): String {
    val totalSeconds = (value / 1000L).coerceAtLeast(0L)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
