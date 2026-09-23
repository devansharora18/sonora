package dev.sonora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.DownloadState
import dev.sonora.backend.LibraryGrouping
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.Playlist
import dev.sonora.backend.Playlists
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.SonoraPlayer
import dev.sonora.ui.theme.accentText
import java.io.File

/**
 * Home is "what's new and what's next", as opposed to the Library's "everything I have".
 *
 * Every row answers a question the Library cannot: where you left off, what arrived recently, what
 * you were looking for, what is downloading. Nothing here is recommended — a catalogue can say what
 * exists, but nothing yet says what is worth hearing — so these are things the user did.
 */
@Composable
fun HomeScreen(onRunSearch: (String) -> Unit, onOpenPlaylist: (String) -> Unit) {
    val context = LocalContext.current
    val tracks by SonoraBackend.library.collectAsState()
    val playlists by SonoraBackend.playlists.collectAsState()
    val history by SonoraBackend.searchHistory.collectAsState()
    val playHistory by SonoraBackend.playHistory.collectAsState()
    val download by SonoraBackend.download.collectAsState()

    LaunchedEffect(Unit) {
        SonoraBackend.refreshLibrary(context)
        SonoraBackend.refreshPlaylists(context)
        SonoraBackend.refreshSearchHistory(context)
        SonoraBackend.refreshPlayHistory(context)
    }

    val recentAlbums = remember(tracks) { LibraryGrouping.recentAlbums(tracks, limit = 12) }

    // Resolved against the library rather than remembered as tracks: the filesystem is the library,
    // so a file that has since been deleted drops out and the rest are current.
    val byPath = remember(tracks) { tracks.associateBy { it.file.absolutePath } }
    val recentTracks = remember(playHistory, byPath) {
        playHistory.mapNotNull { byPath[it.path] }
    }

    // Liked Songs is a playlist like any other, so it is pinned first rather than shown twice.
    val orderedPlaylists = remember(playlists) {
        playlists.sortedByDescending { it.id == Playlists.LIKED_ID }
    }

    if (tracks.isEmpty() && playlists.isEmpty() && history.isEmpty()) {
        GettingStarted(onRunSearch = onRunSearch)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                text = "Home",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp),
            )
        }

        val active = download
        if (active is DownloadState.Downloading) {
            item {
                DownloadingCard(
                    filename = active.filename,
                    fraction = active.fraction,
                    remaining = active.remaining,
                )
            }
        }

        // First after the download, because it is the most actionable thing on the screen: the
        // other rows say what exists, this one says what you were in the middle of.
        if (recentTracks.isNotEmpty()) {
            item { RecentlyPlayedRow(tracks = recentTracks) }
        }

        if (recentAlbums.isNotEmpty()) {
            item {
                Section(
                    title = "Recently added",
                    subtitle = "The newest music in your library",
                    albums = recentAlbums,
                )
            }
        }

        if (orderedPlaylists.isNotEmpty()) {
            item {
                PlaylistsRow(
                    playlists = orderedPlaylists,
                    byPath = tracks,
                    onOpen = onOpenPlaylist,
                )
            }
        }

        if (history.isNotEmpty()) {
            item { RecentSearchesRow(history = history, onRunSearch = onRunSearch) }
        }
    }
}

@Composable
private fun RecentlyPlayedRow(tracks: List<LibraryTrack>) {
    val context = LocalContext.current

    Column {
        SectionHeader(title = "Recently played", subtitle = "Pick up where you left off")

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(tracks, key = { _, track -> track.file.absolutePath }) { index, track ->
                MediaCard(
                    artwork = rememberArtwork(track.file),
                    title = track.title,
                    subtitle = listOfNotNull(track.artist, track.album).joinToString("  \u00b7  "),
                    shape = RoundedCornerShape(8.dp),
                    // The row becomes the queue, so next and previous carry on down it rather than
                    // stopping at the one track that was tapped.
                    onClick = { SonoraPlayer.play(context, tracks, index) },
                )
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    subtitle: String,
    albums: List<LibraryGrouping.Album>,
) {
    val context = LocalContext.current

    Column {
        SectionHeader(title = title, subtitle = subtitle)

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums, key = { it.name + it.artist }) { album ->
                val file = album.tracks.firstOrNull()?.file

                MediaCard(
                    artwork = if (file != null) rememberArtwork(file) else null,
                    title = album.name,
                    subtitle = album.artist,
                    shape = RoundedCornerShape(8.dp),
                    onClick = { SonoraPlayer.play(context, album.tracks, 0) },
                )
            }
        }
    }
}

@Composable
private fun PlaylistsRow(
    playlists: List<Playlist>,
    byPath: List<LibraryTrack>,
    onOpen: (String) -> Unit,
) {
    val paths = remember(byPath) { byPath.mapTo(HashSet()) { it.file.absolutePath } }

    Column {
        SectionHeader(title = "Your playlists", subtitle = "Collections you have made")

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(playlists, key = { it.id }) { playlist ->
                val liked = playlist.id == Playlists.LIKED_ID

                MediaCard(
                    artwork = null,
                    icon = if (liked) Icons.Filled.Favorite else Icons.AutoMirrored.Filled.QueueMusic,
                    title = playlist.name,
                    subtitle = run {
                        val count = playlist.trackPaths.count { it in paths }
                        if (count == 1) "1 track" else "$count tracks"
                    },
                    shape = if (liked) CircleShape else RoundedCornerShape(8.dp),
                    onClick = { onOpen(playlist.id) },
                )
            }
        }
    }
}

@Composable
private fun RecentSearchesRow(history: List<String>, onRunSearch: (String) -> Unit) {
    Column {
        SectionHeader(title = "Keep looking", subtitle = "Searches you have run")

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(history, key = { it }) { term ->
                MediaCard(
                    artwork = null,
                    icon = Icons.Filled.Search,
                    title = term,
                    subtitle = "Search again",
                    shape = RoundedCornerShape(8.dp),
                    onClick = { onRunSearch(term) },
                )
            }
        }
    }
}



@Composable
private fun DownloadingCard(filename: String, fraction: Float?, remaining: Int) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = "Downloading",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = buildString {
                append(filename)
                if (remaining > 0) append("  \u00b7  $remaining queued")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction ?: 0f)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.accentText),
            )
        }
    }
}

@Composable
private fun GettingStarted(onRunSearch: (String) -> Unit) {
    val history by SonoraBackend.searchHistory.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Sonora", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Search the Soulseek network to find music. What you download shows up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (history.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Try again:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onRunSearch(history.first()) }) {
                    Text(history.first())
                }
            }
        }
    }
}
