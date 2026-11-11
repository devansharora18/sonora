package dev.sonora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.foundation.layout.BoxWithConstraints
import dev.sonora.backend.asLibraryTrack
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
fun HomeScreen(
    onRunSearch: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onImportSpotify: () -> Unit = {},
    onOpenRadio: () -> Unit = {},
) {
    val context = LocalContext.current
    val tracks by SonoraBackend.library.collectAsState()
    val playlists by SonoraBackend.playlists.collectAsState()
    val history by SonoraBackend.searchHistory.collectAsState()
    val playHistory by SonoraBackend.playHistory.collectAsState()
    val download by SonoraBackend.download.collectAsState()
    val playing = SonoraPlayer.state.collectAsState().value.track != null

    LaunchedEffect(Unit) {
        SonoraBackend.refreshLibrary(context)
        SonoraBackend.refreshPlaylists(context)
        SonoraBackend.refreshSearchHistory(context)
        SonoraBackend.refreshPlayHistory(context)
    }

    // The first few lead the page as cards you can see, and the rest go in a shelf underneath.
    // Splitting here rather than in the shelf keeps "which albums lead" a decision about the page
    // rather than something each shelf re-decides for itself.
    val recentAlbums = remember(tracks) { LibraryGrouping.recentAlbums(tracks, limit = HERO_COUNT) }
    val moreAlbums = remember(tracks) {
        LibraryGrouping.recentAlbums(tracks, limit = HERO_COUNT + SHELF_COUNT)
            .drop(HERO_COUNT)
    }

    // Resolved against the library rather than remembered as tracks: the filesystem is the library,
    // so a file that has since been deleted drops out and the rest are current.
    val byKey = remember(tracks) { tracks.associateBy { it.key } }
    // A streamed track is resolved from the entry itself. The library is the scan, and a stream has
    // no file for the scan to find, so a "Recently played" row resolved only against it is empty for
    // everything played from YouTube Music — the row that is meant to be the quickest way back into
    // a song is the one place the song is not there.
    val recentTracks = remember(playHistory, byKey) {
        playHistory.mapNotNull { it.asLibraryTrack() ?: byKey[it.key] }
    }

    // Liked Songs is a playlist like any other, so it is pinned first rather than shown twice.
    val orderedPlaylists = remember(playlists) {
        playlists.sortedByDescending { it.id == Playlists.LIKED_ID }
    }

    val activeDownload = download as? DownloadState.Downloading

    // Whether there is anything of the listener's own. Decided once and used three times — the
    // heading, the row of downloads, and whether the shelves are the whole page — because a page
    // that decides it separately each time ends up with a heading over nothing.
    val hasSomethingOfMine = tracks.isNotEmpty() || playlists.isNotEmpty() || history.isNotEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = listContentPadding(extra = 24.dp, withMiniPlayer = playing),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // The page's own name, and only when there is something under it. A heading over an empty
        // shelf reads as a section that failed to load rather than as one with nothing in it yet.
        if (hasSomethingOfMine) {
            item {
                Text(
                    text = "Listen now",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                )
            }
        }

        // First, because it is the one thing here that starts something rather than resuming it:
        // every other row is about what already exists.
        item(key = "radio-entry") { RadioEntryCard(onClick = onOpenRadio) }

        val active = download
        if (active is DownloadState.Downloading) {
            item {
                DownloadingCard(
                    filename = active.filename,
                    fraction = active.fraction,
                    remaining = active.remaining,
                    onCancel = { SonoraBackend.cancelDownload() },
                )
            }
        }

        // The one thing the page leads with, and the only card on it that is bigger than a shelf
        // card. It is the album you were in the middle of, so it goes first and everything else
        // settles into the ordinary size that says "one of several".
        if (recentAlbums.isNotEmpty()) {
            item {
                HeroShelf(
                    title = "Recently added",
                    subtitle = "The newest music in your library",
                    cards = recentAlbums.map { album ->
                        HeroEntry(
                            title = album.name,
                            subtitle = album.artist,
                            artwork = album.tracks.firstOrNull()?.let { rememberTrackArtwork(it, px = CARD_ART_PX) },
                            tracks = album.tracks,
                        )
                    },
                    onOpen = { entry -> SonoraPlayer.play(context, entry.tracks, 0) },
                )
            }
        }

        // Second, because it is a question about one specific record and the shelves above are
        // about the library as a whole.
        if (recentTracks.isNotEmpty()) {
            item { RecentlyPlayedRow(tracks = recentTracks) }
        }

        if (moreAlbums.isNotEmpty()) {
            item { Section(title = "Albums", subtitle = "Everything in your library", albums = moreAlbums) }
        }

        // Unconditional, because the row leads with the import tile — which is the only way a
        // playlist gets made here from somewhere else, and is therefore most needed by exactly the
        // people who have no playlists yet. Gating it on `orderedPlaylists` would hide the import
        // from every new install, which is the one case it is for.
        item {
            PlaylistsRow(
                onImportSpotify = onImportSpotify,
                playlists = orderedPlaylists,
                tracks = tracks,
                onOpen = onOpenPlaylist,
            )
        }

        if (history.isNotEmpty()) {
            item { RecentSearchesRow(history = history, onRunSearch = onRunSearch) }
        }
    }
}

/**
 * The way into Radio from the page a listener actually opens.
 *
 * A card rather than a tab or a menu entry: starting a mix is the one thing on Home that does not
 * depend on having found something first, so it is worth a shape of its own.
 */
@Composable
private fun RadioEntryCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.accentText),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Radio,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.background,
                modifier = Modifier.size(26.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = "Start a radio",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Pick a few artists, then choose how far out it reaches",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentlyPlayedRow(tracks: List<LibraryTrack>) {
    val context = LocalContext.current

    Column(modifier = Modifier.padding(bottom = 26.dp)) {
        ShelfHeader(title = "Recently played", subtitle = "Pick up where you left off")

        // Rows rather than cards, paged sideways in columns. Recents is the one shelf where the
        // question is "what was that one called" rather than "what should I play", and a list of
        // titles answers it where a wall of covers does not. Four to a column so the next page is
        // visible without paging.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columnWidth = trackColumnWidth(maxWidth)

            LazyRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(
                    tracks.chunked(RECENT_TRACKS_PER_COLUMN),
                    key = { _, page -> page.firstOrNull()?.key ?: "empty" },
                ) { _, page ->
                    Column(Modifier.width(columnWidth)) {
                        page.forEach { track ->
                            val index = tracks.indexOf(track)
                            SongRow(
                                track = track,
                                // The whole recents list becomes the queue, so next and previous
                                // carry on down it rather than stopping at the tapped track.
                                onClick = { SonoraPlayer.play(context, tracks, index) },
                                isCurrent = false,
                                meta = listOfNotNull(track.artist).joinToString("  \u00b7  "),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** How many track rows make a page of the recents shelf. */
private const val RECENT_TRACKS_PER_COLUMN = 4

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
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(SHELF_SPACING),
        ) {
            items(albums, key = { it.name + it.artist }) { album ->
                // Taken from the first track: a grouping has no artwork of its own, and the first
                // track is the one whose cover the release actually uses.
                val first = album.tracks.firstOrNull()
                val artwork = when {
                    first?.file != null -> rememberArtwork(first.file)
                    else -> first?.let { rememberTrackArtwork(it, px = CARD_ART_PX) }
                }

                ShelfCard(
                    artwork = artwork,
                    title = album.name,
                    subtitle = album.artist,
                    onClick = { SonoraPlayer.play(context, album.tracks, 0) },
                )
            }
        }
    }
}

@Composable
private fun PlaylistsRow(
    playlists: List<Playlist>,
    tracks: List<LibraryTrack>,
    onOpen: (String) -> Unit,
    onImportSpotify: () -> Unit,
) {
    val keys = remember(tracks) { tracks.mapTo(HashSet()) { it.key } }
    // Every key to its track, so a playlist's cover can be built out of the tracks in it rather
    // than being an icon: a playlist is a set of records and has no picture of its own.
    val byKey = remember(tracks) { tracks.associateBy { it.key } }

    Column {
        SectionHeader(
            title = "Your playlists",
            subtitle = "Collections you have made, and where new ones come from",
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The import tile leads, because for anyone arriving from Spotify with a link already
            // copied it is the thing they came to do — and it is the only way a playlist gets made
            // here from somewhere else.
            item(key = "import") {
                ShelfCard(
                    artwork = null,
                    icon = Icons.Filled.Download,
                    title = "Import from Spotify",
                    subtitle = "Paste a playlist link",
                    onClick = onImportSpotify,
                )
            }

            items(playlists, key = { it.id }) { playlist ->
                val liked = playlist.id == Playlists.LIKED_ID
                // Resolved against the library, in the playlist's own order: a mosaic is a cover, and
                // a cover of a playlist is the first few things in it.
                val contents = playlist.trackKeys.mapNotNull { byKey[it] }

                ShelfCard(
                    artwork = null,
                    mosaic = contents.take(MOSAIC_TRACKS),
                    icon = playlistIcon(liked),
                    title = playlist.name,
                    subtitle = run {
                        val count = contents.size
                        if (count == 1) "1 track" else "$count tracks"
                    },
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
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(history, key = { it }) { term ->
                ShelfCard(
                    artwork = null,
                    icon = Icons.Filled.Search,
                    title = term,
                    subtitle = "Search again",
                    onClick = { onRunSearch(term) },
                )
            }
        }
    }
}



@Composable
private fun DownloadingCard(
    filename: String,
    fraction: Float?,
    remaining: Int,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = PAGE_GUTTER)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
            }
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cancel download",
                    modifier = Modifier.size(18.dp),
                )
            }
        }

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

/** How many albums lead the page as hero cards. */
private const val HERO_COUNT = 4

/** How many cards a square shelf holds before "show all" is worth offering. */
private const val SHELF_COUNT = 10

