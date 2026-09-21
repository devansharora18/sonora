package dev.sonora.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.PlaybackState
import dev.sonora.backend.Playlist
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.SonoraPlayer

private enum class LibrarySection(val label: String) {
    Tracks("Tracks"),
    Playlists("Playlists"),
}

@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val tracks by SonoraBackend.library.collectAsState()
    val playlists by SonoraBackend.playlists.collectAsState()
    val playback by SonoraPlayer.state.collectAsState()
    var section by remember { mutableStateOf(LibrarySection.Tracks) }
    var creating by remember { mutableStateOf(false) }

    // The filesystem is the source of truth, so rescan whenever this screen is shown.
    LaunchedEffect(Unit) {
        SonoraBackend.refreshLibrary(context)
        SonoraBackend.refreshPlaylists(context)
    }

    // Resolved once here: the library scan is what decides whether a stored path still exists, and
    // a playlist should report and play what is actually there.
    val byPath = remember(tracks) { tracks.associateBy { it.file.absolutePath } }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Your Library",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
        )

        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibrarySection.entries.forEach { entry ->
                FilterChip(
                    selected = section == entry,
                    onClick = { section = entry },
                    label = {
                        Text(entry.label, style = MaterialTheme.typography.labelMedium)
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (section == entry) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    ),
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (section) {
                LibrarySection.Tracks -> TracksSection(tracks, playback, context)

                LibrarySection.Playlists -> PlaylistsSection(
                    playlists = playlists,
                    byPath = byPath,
                    onCreate = { creating = true },
                    onPlay = { playlist ->
                        val playable = playlist.trackPaths.mapNotNull { byPath[it] }
                        if (playable.isNotEmpty()) SonoraPlayer.play(context, playable, 0)
                    },
                )
            }
        }
    }

    if (creating) {
        CreatePlaylistDialog(
            onDismiss = { creating = false },
            onCreate = { SonoraBackend.createPlaylist(context, it) },
        )
    }
}

@Composable
private fun TracksSection(
    tracks: List<LibraryTrack>,
    playback: PlaybackState,
    context: Context,
) {
    if (tracks.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.MusicNote,
            title = "Your library is empty",
            message = "Search for music, then download a track to see it here.",
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "${tracks.size} downloaded tracks",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(tracks, key = { _, track -> track.file.absolutePath }) { index, track ->
                TrackRow(
                    track = track,
                    isPlaying = playback.isPlaying && playback.track?.file == track.file,
                    onPlay = { SonoraPlayer.play(context, tracks, index) },
                )
            }
        }
    }
}

@Composable
private fun PlaylistsSection(
    playlists: List<Playlist>,
    byPath: Map<String, LibraryTrack>,
    onCreate: () -> Unit,
    onPlay: (Playlist) -> Unit,
) {
    if (playlists.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize()) {
            NewPlaylistRow(onCreate = onCreate)
            EmptyState(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = "No playlists yet",
                message = "Create one to keep tracks together.",
                modifier = Modifier.weight(1f),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { NewPlaylistRow(onCreate = onCreate) }

        items(playlists, key = { it.id }) { playlist ->
            PlaylistRow(
                playlist = playlist,
                trackCount = playlist.trackPaths.count { it in byPath },
                onClick = { onPlay(playlist) },
            )
        }
    }
}

@Composable
private fun NewPlaylistRow(onCreate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCreate)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tile { Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        Text(
            text = "New playlist",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, trackCount: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tile {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
        ) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (trackCount == 1) "1 track" else "$trackCount tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TrackRow(track: LibraryTrack, isPlaying: Boolean, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tile {
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
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isPlaying) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = listOfNotNull(track.artist, track.album, formatBytes(track.size))
                    .joinToString("  \u00b7  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The 56dp leading square shared by track and playlist rows, so the two lists line up. */
@Composable
private fun Tile(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        // The scheme does not define the surface-container roles, so without this the dialog falls
        // back to the Material baseline palette and reads as off-brand purple.
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onCreate(name)
                    onDismiss()
                },
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
