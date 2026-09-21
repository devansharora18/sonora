package dev.sonora.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.LibraryGrouping
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.MusicDirectory
import dev.sonora.backend.PlaybackState
import dev.sonora.backend.Playlist
import dev.sonora.backend.Playlists
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.SonoraPlayer
import dev.sonora.ui.theme.accentText

private enum class LibrarySection(val label: String) {
    Tracks("Tracks"),
    Albums("Albums"),
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
    var openPlaylistId by remember { mutableStateOf<String?>(null) }
    var openAlbum by remember { mutableStateOf<LibraryGrouping.Album?>(null) }
    var addTarget by remember { mutableStateOf<LibraryTrack?>(null) }
    var deleteTarget by remember { mutableStateOf<LibraryTrack?>(null) }
    var failedDelete by remember { mutableStateOf<LibraryTrack?>(null) }

    // The filesystem is the source of truth, so rescan whenever this screen is shown.
    LaunchedEffect(Unit) {
        SonoraBackend.refreshLibrary(context)
        SonoraBackend.refreshPlaylists(context)
    }

    // Resolved once here: the library scan is what decides whether a stored path still exists, and
    // a playlist should report and play what is actually there.
    val byPath = remember(tracks) { tracks.associateBy { it.file.absolutePath } }
    val likedPaths = remember(playlists) { Playlists.likedPaths(playlists) }
    val albums = remember(tracks) { LibraryGrouping.albums(tracks) }

    // Delete is only offered for files in the download folder. Now that the library also lists music
    // from the rest of the device, offering to delete someone's own collection would be wrong.
    val downloadDirectory = remember { MusicDirectory.resolve(context).directory.absolutePath }

    // Held by id, not by value, so a rename or a removal is reflected immediately — and so a
    // deleted playlist closes the screen instead of showing a stale copy.
    val open = openPlaylistId?.let { id -> playlists.firstOrNull { it.id == id } }
    val album = openAlbum

    if (album != null) {
        BackHandler { openAlbum = null }
        AlbumDetailScreen(
            album = album,
            onBack = { openAlbum = null },
            onPlayFrom = { index -> SonoraPlayer.play(context, album.tracks, index) },
        )
        return
    }

    if (open != null) {
        val contents = open.trackPaths.mapNotNull { byPath[it] }

        BackHandler { openPlaylistId = null }
        PlaylistDetailScreen(
            playlist = open,
            tracks = contents,
            onBack = { openPlaylistId = null },
            onPlayFrom = { index -> SonoraPlayer.play(context, contents, index) },
            onRemove = { track ->
                SonoraBackend.removeFromPlaylist(context, open.id, track.file.absolutePath)
            },
            onRename = { name -> SonoraBackend.renamePlaylist(context, open.id, name) },
            onDelete = {
                SonoraBackend.deletePlaylist(context, open.id)
                openPlaylistId = null
            },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Your Library",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
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
                            MaterialTheme.colorScheme.accentText
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    ),
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (section) {
                LibrarySection.Tracks -> TracksSection(
                    tracks = tracks,
                    playback = playback,
                    context = context,
                    likedPaths = likedPaths,
                    downloadDirectory = downloadDirectory,
                    onToggleLike = { SonoraBackend.toggleLiked(context, it) },
                    onAddToPlaylist = { addTarget = it },
                    onDelete = { deleteTarget = it },
                )

                LibrarySection.Albums -> AlbumsSection(
                    albums = albums,
                    onOpen = { openAlbum = it },
                )

                LibrarySection.Playlists -> PlaylistsSection(
                    playlists = playlists,
                    byPath = byPath,
                    onCreate = { creating = true },
                    onOpen = { openPlaylistId = it.id },
                )
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "New playlist",
            initialName = "",
            confirmLabel = "Create",
            onDismiss = { creating = false },
            onConfirm = { SonoraBackend.createPlaylist(context, it) },
        )
    }

    AddToPlaylistFlow(
        track = addTarget,
        playlists = playlists,
        onDismiss = { addTarget = null },
        onAdd = { playlist, track ->
            SonoraBackend.addToPlaylist(context, playlist.id, track)
            addTarget = null
        },
        onCreateWithTrack = { name, track ->
            SonoraBackend.createPlaylist(context, name, track)
            addTarget = null
        },
    )

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Delete download?") },
            text = {
                Text("\u201c${deleteTarget?.title}\u201d will be removed from this device.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val track = deleteTarget
                        deleteTarget = null
                        if (track != null) {
                            // The queue would otherwise keep items whose files no longer exist.
                            if (playback.track?.file == track.file) SonoraPlayer.stop()

                            if (!SonoraBackend.deleteDownload(context, track)) {
                                failedDelete = track
                            }
                        }
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (failedDelete != null) {
        AlertDialog(
            onDismissRequest = { failedDelete = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Couldn't delete") },
            text = {
                Text(
                    "\u201c${failedDelete?.title}\u201d wasn't downloaded by Sonora, so Android " +
                        "doesn't allow removing it from here. Use a file manager instead.",
                )
            },
            confirmButton = {
                TextButton(onClick = { failedDelete = null }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun TracksSection(
    tracks: List<LibraryTrack>,
    playback: PlaybackState,
    context: Context,
    likedPaths: Set<String>,
    downloadDirectory: String,
    onToggleLike: (LibraryTrack) -> Unit,
    onAddToPlaylist: (LibraryTrack) -> Unit,
    onDelete: (LibraryTrack) -> Unit,
) {
    if (tracks.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.MusicNote,
            title = "No music found",
            message = "Search for music and download a track, or add files to Music/Soulseek.",
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = if (tracks.size == 1) "1 track" else "${tracks.size} tracks",
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
                    isLiked = track.file.absolutePath in likedPaths,
                    canDelete = track.file.parentFile?.absolutePath == downloadDirectory,
                    onPlay = { SonoraPlayer.play(context, tracks, index) },
                    onToggleLike = { onToggleLike(track) },
                    onAddToPlaylist = { onAddToPlaylist(track) },
                    onDelete = { onDelete(track) },
                )
            }
        }
    }
}

@Composable
private fun AlbumsSection(
    albums: List<LibraryGrouping.Album>,
    onOpen: (LibraryGrouping.Album) -> Unit,
) {
    if (albums.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Album,
            title = "No albums found",
            message = "Download music, or add files to Music/Soulseek.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(albums, key = { it.name + it.artist }) { album ->
            AlbumRow(album = album, onClick = { onOpen(album) })
        }
    }
}

@Composable
private fun AlbumRow(album: LibraryGrouping.Album, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tile {
            // Artwork from the album's first track: the album has no artwork of its own, and the
            // files of one album normally carry the same embedded cover.
            val artwork = album.tracks.firstOrNull()?.let { rememberArtwork(it.file) }
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
                text = album.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(
                    album.artist,
                    if (album.tracks.size == 1) "1 track" else "${album.tracks.size} tracks",
                ).joinToString("  \u00b7  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlaylistsSection(
    playlists: List<Playlist>,
    byPath: Map<String, LibraryTrack>,
    onCreate: () -> Unit,
    onOpen: (Playlist) -> Unit,
) {
    // Pinned first and shown even before anything is liked, so it is discoverable rather than
    // appearing only after the user has already worked out how to like something.
    val liked = playlists.firstOrNull { it.id == Playlists.LIKED_ID }
        ?: Playlist(id = Playlists.LIKED_ID, name = Playlists.LIKED_NAME)
    val others = playlists.filterNot { it.id == Playlists.LIKED_ID }
    val all = listOf(liked) + others

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { NewPlaylistRow(onCreate = onCreate) }

        items(all, key = { it.id }) { playlist ->
            PlaylistRow(
                playlist = playlist,
                trackCount = playlist.trackPaths.count { it in byPath },
                reserved = playlist.id == Playlists.LIKED_ID,
                onClick = { onOpen(playlist) },
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
        Tile {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.accentText,
            )
        }
        Text(
            text = "New playlist",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.accentText,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    trackCount: Int,
    reserved: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tile {
            Icon(
                imageVector = if (reserved) {
                    Icons.Filled.Favorite
                } else {
                    Icons.AutoMirrored.Filled.QueueMusic
                },
                contentDescription = null,
                tint = if (reserved) {
                    MaterialTheme.colorScheme.accentText
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
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
private fun TrackRow(
    track: LibraryTrack,
    isPlaying: Boolean,
    isLiked: Boolean,
    canDelete: Boolean,
    onPlay: () -> Unit,
    onToggleLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDelete: () -> Unit,
) {
    TrackListRow(
        track = track,
        meta = listOfNotNull(track.artist, track.album, formatBytes(track.size))
            .joinToString("  \u00b7  "),
        isPlaying = isPlaying,
        onClick = onPlay,
        trailing = {
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

            // Deleting sits behind a menu rather than on the row: a bare delete icon beside every
            // track is one mis-tap away from destroying music, which is not recoverable.
            var menuOpen by remember { mutableStateOf(false) }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Track options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Add to playlist") },
                        onClick = {
                            menuOpen = false
                            onAddToPlaylist()
                        },
                    )
                    if (canDelete) {
                        DropdownMenuItem(
                            text = { Text("Delete download") },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
