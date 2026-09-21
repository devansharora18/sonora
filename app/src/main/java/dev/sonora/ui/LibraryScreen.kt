package dev.sonora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.SonoraPlayer

@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val tracks by SonoraBackend.library.collectAsState()
    val playback by SonoraPlayer.state.collectAsState()

    // The filesystem is the source of truth, so rescan whenever this screen is shown.
    LaunchedEffect(Unit) { SonoraBackend.refreshLibrary(context) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (tracks.isEmpty()) {
            Text(
                text = "Nothing downloaded yet. Search, then tap a result.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            return@Column
        }

        Text(
            text = "${tracks.size} track(s)",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(tracks, key = { it.file.absolutePath }) { track ->
                TrackRow(
                    track = track,
                    isPlaying = playback.isPlaying && playback.track?.file == track.file,
                    onPlay = { SonoraPlayer.play(context, track) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TrackRow(track: LibraryTrack, isPlaying: Boolean, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        rememberArtwork(track.file)?.let { artwork ->
            Image(
                bitmap = artwork,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isPlaying) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = listOfNotNull(track.artist, track.album, formatBytes(track.size))
                    .joinToString("  \u00b7  "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
