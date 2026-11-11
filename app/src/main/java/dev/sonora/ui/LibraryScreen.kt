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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.sonora.backend.LibraryGrouping
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.MusicDirectory
import dev.sonora.backend.PageKind
import dev.sonora.backend.PageRequest
import dev.sonora.backend.PlaybackState
import dev.sonora.backend.Playlist
import dev.sonora.backend.Playlists
import dev.sonora.backend.SearchQueries
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.SonoraPlayer
import dev.sonora.ui.theme.accentText

private enum class LibrarySection(val label: String) {
    Tracks("Tracks"),
    Albums("Albums"),
    Artists("Artists"),
    Playlists("Playlists"),
}

@Composable
fun LibraryScreen(
    onRunSearch: (String) -> Unit,
    /**
     * The playlist being shown, or null for the list.
     *
     * Held by the caller rather than here so that Home can open one: a card there is a playlist,
     * and tapping it should land on the same screen the Library would have shown.
     */
    openPlaylistId: String?,
    onOpenPlaylist: (String) -> Unit,
    onClosePlaylist: () -> Unit,
    openArtistName: String? = null,
    onCloseArtist: () -> Unit = {},
    openAlbumName: String? = null,
    onCloseAlbum: () -> Unit = {},
    /**
     * A page opened from elsewhere — a search result, or the artist line on the player — which
     * carries the browse id as well as the name. This is what makes such a page show the artist's
     * own songs rather than only the ones already on the phone.
     */
    openPage: PageRequest? = null,
    onClosePage: () -> Unit = {},
    /** Sends the listener to the sign-in page. Held here because this is the screen that asks. */
    onNeedPeers: () -> Unit = {},
    onStartRadio: (String) -> Unit = {},
) {
    // Somewhere to send the listener when a file was wanted and there was no network to ask.
    var needPeers by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val tracks by SonoraBackend.library.collectAsState()
    val playlists by SonoraBackend.playlists.collectAsState()
    val playback by SonoraPlayer.state.collectAsState()
    var section by remember { mutableStateOf(LibrarySection.Tracks) }
    var creating by remember { mutableStateOf(false) }
    var openAlbum by remember { mutableStateOf<LibraryGrouping.Album?>(null) }
    var openArtist by remember { mutableStateOf<LibraryGrouping.Artist?>(null) }
    // Set from a row's own menu, so the name travels the same way it does when the player hands one
    // over. Held here rather than only as a parameter because a sheet is a child of this screen and
    // has no way to reach the caller's state.
    var albumName by remember { mutableStateOf<String?>(null) }
    var artistName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openAlbumName) { if (openAlbumName != null) albumName = openAlbumName }
    LaunchedEffect(openArtistName) { if (openArtistName != null) artistName = openArtistName }

    // The page somebody opened from elsewhere. Asked for once per request and then held, because
    // the answer is cached on the backend and a recomposition must not turn into another request.
    val browsed by SonoraBackend.browsed.collectAsState()
    LaunchedEffect(openPage?.cacheKey, openPage?.artist) {
        val page = openPage ?: return@LaunchedEffect
        SonoraBackend.browse(page)
    }
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
    val byKey = remember(tracks) { tracks.associateBy { it.key } }
    val likedKeys = remember(playlists) { Playlists.likedKeys(playlists) }
    val albums = remember(tracks) { LibraryGrouping.albums(tracks) }
    val artists = remember(tracks) { LibraryGrouping.artists(tracks) }

    // Delete is only offered for files in the download folder. Now that the library also lists music
    // from the rest of the device, offering to delete someone's own collection would be wrong.
    val downloadDirectory = remember { MusicDirectory.resolve(context).directory.absolutePath }

    // Held by id, not by value, so a rename or a removal is reflected immediately — and so a
    // deleted playlist closes the screen instead of showing a stale copy.
    val open = openPlaylistId?.let { id -> playlists.firstOrNull { it.id == id } }

    val browsedAlbumPage = openPage?.takeIf { it.kind == PageKind.ALBUM }?.let { page ->
        browsed[page.cacheKey] ?: page.browseId?.let { browsed[it] }
    }
    val remoteAlbum = browsedAlbumPage
        ?.page
        ?.tracks
        ?.map { LibraryTrack.fromRemote(it) }
        .orEmpty()

    val album = openAlbum ?: when {
        openPage?.kind == PageKind.ALBUM -> {
            val title = browsedAlbumPage?.page?.title?.takeIf { it.isNotBlank() } ?: openPage.name
            val albumArtist = remoteAlbum.firstNotNullOfOrNull { it.artist }
                ?: openPage.artist
                ?: LibraryGrouping.UNKNOWN_ARTIST
            LibraryGrouping.Album(
                name = title,
                artist = albumArtist,
                tracks = remoteAlbum,
            )
        }
        else -> (albumName ?: openAlbumName)?.let { name ->
            albums.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: playback.track?.takeIf { it.album?.equals(name, ignoreCase = true) == true }?.let { t ->
                    LibraryGrouping.Album(name = name, artist = t.artist ?: LibraryGrouping.UNKNOWN_ARTIST, tracks = listOf(t))
                }
        }
    }

    if (album != null) {
        BackHandler {
            openAlbum = null
            albumName = null
            onClosePage()
            onCloseAlbum()
        }
        AlbumDetailScreen(
            album = album,
            onBack = {
                openAlbum = null
                albumName = null
                onClosePage()
                onCloseAlbum()
            },
            onPlayFrom = { index -> SonoraPlayer.play(context, album.tracks, index) },
            onFindMore = onRunSearch,
            artworkUrl = browsedAlbumPage?.page?.artworkUrl ?: openPage?.artworkUrl,
            playing = playback.track != null,
        )
        return
    }

    val browsedArtistPage = openPage?.takeIf { it.kind == PageKind.ARTIST }?.let { page ->
        browsed[page.cacheKey] ?: page.browseId?.let { browsed[it] }
    }
    val remoteArtistTracks = browsedArtistPage
        ?.page
        ?.tracks
        ?.map { LibraryTrack.fromRemote(it) }
        .orEmpty()

    val artist = openArtist ?: when {
        openPage?.kind == PageKind.ARTIST -> {
            val title = browsedArtistPage?.page?.title?.takeIf { it.isNotBlank() } ?: openPage.name
            LibraryGrouping.Artist(
                name = title,
                tracks = remoteArtistTracks,
            )
        }
        else -> (artistName ?: openArtistName)?.let { name ->
            artists.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: playback.track?.takeIf { it.artist?.equals(name, ignoreCase = true) == true }?.let { t ->
                    LibraryGrouping.Artist(name = name, tracks = listOf(t))
                }
        }
    }

    val artistAlbums = browsedArtistPage?.page?.albums.orEmpty()
    val artistSingles = browsedArtistPage?.page?.singles.orEmpty()

    if (artist != null) {
        BackHandler {
            openArtist = null
            artistName = null
            onClosePage()
            onCloseArtist()
        }
        ArtistDetailScreen(
            artist = artist,
            remoteAlbums = artistAlbums,
            remoteSingles = artistSingles,
            artworkUrl = browsedArtistPage?.page?.artworkUrl
                ?: openPage?.artworkUrl
                ?: playback.track?.takeIf { it.artist.equals(artist.name, ignoreCase = true) }?.artworkUrl,
            playing = playback.track != null,
            onBack = {
                openArtist = null
                artistName = null
                onClosePage()
                onCloseArtist()
            },
            onPlayFrom = { index -> SonoraPlayer.play(context, artist.tracks, index) },
            onFindMore = onRunSearch,
        )
        return
    }

    if (open != null) {
        val contents = open.trackKeys.mapNotNull { byKey[it] }

        BackHandler { onClosePlaylist() }
        PlaylistDetailScreen(
            playlist = open,
            tracks = contents,
            onBack = { onClosePlaylist() },
            onPlayFrom = { index -> SonoraPlayer.play(context, contents, index) },
            onRemove = { track ->
                SonoraBackend.removeFromPlaylist(context, open.id, track.key)
            },
            onRename = { name -> SonoraBackend.renamePlaylist(context, open.id, name) },
            onDelete = {
                SonoraBackend.deletePlaylist(context, open.id)
                onClosePlaylist()
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Text(
            text = "Library",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        )

        ChoicePillShelf {
            items(LibrarySection.entries.size, key = { LibrarySection.entries[it].name }) { index ->
                val entry = LibrarySection.entries[index]

                ChoicePill(
                    label = entry.label,
                    selected = section == entry,
                    onClick = { section = entry },
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (section) {
                LibrarySection.Tracks -> TracksSection(
                    tracks = tracks,
                    playback = playback,
                    context = context,
                    likedKeys = likedKeys,
                    downloadDirectory = downloadDirectory,
                    onToggleLike = { SonoraBackend.toggleLiked(context, it) },
                    onAddToPlaylist = { addTarget = it },
                    onDelete = { deleteTarget = it },
                    onFindLossless = { onRunSearch(it) },
                    onForget = { SonoraBackend.unsave(context, it) },
                    onOpenAlbum = { albumName = it },
                    onOpenArtist = { artistName = it },
                    onNeedPeers = { needPeers = true },
                    onStartRadio = onStartRadio,
                    scanning = SonoraBackend.scanning.collectAsState().value,
                )

                LibrarySection.Albums -> AlbumsSection(
                    albums = albums,
                    onOpen = { openAlbum = it },
                )

                LibrarySection.Artists -> ArtistsSection(
                    artists = artists,
                    onOpen = { openArtist = it },
                )

                LibrarySection.Playlists -> PlaylistsSection(
                    playlists = playlists,
                    byKey = byKey,
                    onCreate = { creating = true },
                    onOpen = { onOpenPlaylist(it.id) },
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

    ConnectToPeersSheet(
        open = needPeers,
        onConnect = {
            needPeers = false
            onNeedPeers()
        },
        onDismiss = { needPeers = false },
    )

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
    likedKeys: Set<String>,
    downloadDirectory: String,
    onToggleLike: (LibraryTrack) -> Unit,
    onAddToPlaylist: (LibraryTrack) -> Unit,
    onDelete: (LibraryTrack) -> Unit,
    /** Takes a query, not a track: the caller turns a track into the search that would find it. */
    onFindLossless: (String) -> Unit,
    onForget: (LibraryTrack) -> Unit,
    /** Opens the album or artist page the sheet's link rows name. */
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onNeedPeers: () -> Unit,
    onStartRadio: (String) -> Unit,
    scanning: Boolean,
) {
    var pending by remember { mutableStateOf<TrackMenu?>(null) }
    // The scan is still running. Saying "no music found" now would be a claim about a folder nobody
    // has finished reading, and it would be replaced a second later by the music that was there all
    // along — so the placeholders stand in, at the real row metrics, and say nothing at all.
    if (scanning && tracks.isEmpty()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            songListSkeleton(count = 9, keyPrefix = "skeleton:library")
        }
        return
    }

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
            modifier = Modifier.padding(start = PAGE_GUTTER, top = 8.dp, bottom = 8.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = listContentPadding(withMiniPlayer = playback.track != null),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(tracks, key = { _, track -> track.key }) { index, track ->
                val canDelete = track.file?.parentFile?.absolutePath == downloadDirectory
                val isLiked = track.key in likedKeys

                SongRow(
                    track = track,
                    onClick = { SonoraPlayer.play(context, tracks, index) },
                    isCurrent = playback.isPlaying && playback.track?.key == track.key,
                    onMore = { pending = TrackMenu(track, canDelete) },
                    // A streaming track has no bytes to count, so printing its zero would read as an
                    // empty file rather than as the absence of one.
                    meta = listOfNotNull(
                        track.artist,
                        track.album,
                        track.file?.let { formatBytes(track.size) },
                    ).joinToString("  \u00b7  "),
                    // The row's only control is its overflow. A heart on every line would be the
                    // same action six times over, and it is in the menu it opens anyway.
                    divider = index < tracks.lastIndex,
                )
            }
        }
    }

    pending?.let { menu ->
        TrackActionsSheet(
            track = menu.track,
            canDelete = menu.canDelete,
            onDismiss = { pending = null },
            onAddToPlaylist = {
                pending = null
                onAddToPlaylist(menu.track)
            },
            onToggleLike = {
                pending = null
                onToggleLike(menu.track)
            },
            onDelete = {
                pending = null
                onDelete(menu.track)
            },
            onForget = {
                pending = null
                onForget(menu.track)
            },
            // Only offered to a track that is not on the device: asking the network for a copy of
            // a file already sitting on the phone is not a thing anyone means.
            onFindLossless = if (menu.track.file != null) {
                null
            } else {
                {
                    pending = null
                    // Peers only, and the listener told when there is no session: a lossless copy
                    // is a file on somebody's disk, and there is nowhere else to look for one.
                    val asked = SonoraBackend.searchPeers(
                        context,
                        SearchQueries.forTrack(
                            menu.track.title,
                            menu.track.artist.orEmpty(),
                        ),
                    )
                    if (!asked) onNeedPeers()
                }
            },
            onPlayNext = {
                pending = null
                SonoraPlayer.playNext(context, menu.track)
            },
            onAddToQueue = {
                pending = null
                SonoraPlayer.addToQueue(context, menu.track)
            },
            // Only where there is something to open. A local file's album and artist are just
            // strings the file happens to carry, and a row that leads to a page of nothing is worse
            // than no row.
            onOpenAlbum = menu.track.album?.takeIf { it.isNotBlank() }?.let {
                {
                    pending = null
                    onOpenAlbum(it)
                }
            },
            onOpenArtist = menu.track.artist?.takeIf { it.isNotBlank() }?.let {
                {
                    pending = null
                    onOpenArtist(it)
                }
            },
            onStartRadio = menu.track.artist?.takeIf { it.isNotBlank() }?.let {
                {
                    pending = null
                    onStartRadio(it)
                }
            },
        )
    }
}

/** What a row's overflow is about to act on. */
private data class TrackMenu(val track: LibraryTrack, val canDelete: Boolean)

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
    ArtworkRow(
        artworkFile = album.tracks.firstOrNull()?.file,
        artworkUrl = album.tracks.firstNotNullOfOrNull { it.artworkUrl ?: it.remote?.artworkUrl },
        title = album.name,
        subtitle = listOf(
            album.artist,
            if (album.tracks.size == 1) "1 track" else "${album.tracks.size} tracks",
        ).joinToString("  \u00b7  "),
        onClick = onClick,
    )
}

@Composable
private fun ArtistsSection(
    artists: List<LibraryGrouping.Artist>,
    onOpen: (LibraryGrouping.Artist) -> Unit,
) {
    if (artists.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Person,
            title = "No artists found",
            message = "Download music, or add files to Music/Soulseek.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(artists, key = { it.name }) { artist ->
            ArtworkRow(
                artworkFile = artist.tracks.firstOrNull()?.file,
                artworkUrl = artist.tracks.firstNotNullOfOrNull { it.artworkUrl ?: it.remote?.artworkUrl },
                title = artist.name,
                subtitle = listOf(
                    run {
                        val albums = LibraryGrouping.albums(artist.tracks).size
                        if (albums == 1) "1 album" else "$albums albums"
                    },
                    if (artist.tracks.size == 1) "1 track" else "${artist.tracks.size} tracks",
                ).joinToString("  \u00b7  "),
                // Circular, which is the convention for an artist as opposed to a release.
                shape = CircleShape,
                onClick = { onOpen(artist) },
            )
        }
    }
}

@Composable
private fun PlaylistsSection(
    playlists: List<Playlist>,
    byKey: Map<String, LibraryTrack>,
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
            val contents = playlist.trackKeys.mapNotNull { byKey[it] }
            PlaylistRow(
                playlist = playlist,
                trackCount = contents.size,
                artwork = contents.take(MOSAIC_TRACKS),
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
            .padding(horizontal = PAGE_GUTTER, vertical = 7.dp),
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
    /** The first few of its tracks, whose covers are the playlist's own picture. */
    artwork: List<LibraryTrack>,
    reserved: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_GUTTER, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistMosaic(
            tracks = artwork,
            modifier = Modifier.size(56.dp),
            icon = playlistIcon(reserved),
        )

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

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
