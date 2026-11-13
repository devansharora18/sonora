package dev.sonora.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import dev.sonora.backend.DownloadState
import dev.sonora.backend.SearchHit
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.SearchQueries
import dev.sonora.ytm.YtmCategory
import dev.sonora.ytm.YtmEntity
import dev.sonora.ytm.YtmPlaylistRef
import dev.sonora.ytm.YtmTrack
import dev.sonora.backend.SonoraPlayer
import dev.sonora.backend.SearchSource
import dev.sonora.backend.SearchState
import dev.sonora.backend.toYtm
import kotlinx.coroutines.delay
import dev.sonora.backend.BackendState
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.SortMode
import dev.sonora.ui.theme.accentText
import dev.sonora.ui.theme.onSurfaceFaint

@Composable
fun SearchScreen(
    /**
     * Opens an album or an artist found here.
     *
     * Handed up rather than opened here because the detail screens belong to the Library, which is
     * where the rest of the app reaches them from — a page that could be entered two ways and drew
     * two different screens would be two screens to keep in step.
     */
    onOpenAlbum: (YtmEntity) -> Unit = {},
    onOpenArtist: (YtmEntity) -> Unit = {},
    onOpenPlaylist: (YtmPlaylistRef) -> Unit = {},
    onOpenCategory: (YtmCategory) -> Unit = {},
    onNeedPeers: () -> Unit = {},
) {
    val context = LocalContext.current
    val searchState by SonoraBackend.search.collectAsState()
    val download by SonoraBackend.download.collectAsState()
    val playing = SonoraPlayer.state.collectAsState().value.track != null
    val settings by SonoraBackend.settings.collectAsState()
    val history by SonoraBackend.searchHistory.collectAsState()
    val entities = searchState.entities
    val shelves by SonoraBackend.shelves.collectAsState()

    // Asked for once the page is on screen rather than at construction: they answer from the
    // network, and a new install's first frame should be the grid arriving rather than a spinner.
    LaunchedEffect(Unit) { SonoraBackend.loadShelves() }
    LaunchedEffect(shelves.categories) {
        val first = shelves.categories.firstOrNull() ?: return@LaunchedEffect
        if (shelves.chosen == null) SonoraBackend.loadCategory(first)
    }
    val sources by SonoraBackend.searchSources.collectAsState()

    // The box is the listener's while they are typing, and the committed query otherwise. It used
    // to be keyed on the committed query, which was fine when a search only happened on submit;
    // with results arriving per pause in typing, re-keying it would rewrite the field under the
    // finger — a trailing space disappearing mid-word is the visible version of that.
    var query by remember { mutableStateOf(searchState.query) }
    var fieldFocused by remember { mutableStateOf(false) }

    LaunchedEffect(searchState.query, fieldFocused) {
        if (!fieldFocused) query = searchState.query
    }

    fun runSearch(term: String) {
        query = term
        if (term.isNotBlank()) SonoraBackend.search(context, term.trim())
    }

    // YT Music's sort control, and a *fastest* source is exactly what a P2P result list needs:
    // without it you pick a peer at random and wait.
    val sort = searchState.sort
    fun onSort(mode: SortMode) = SonoraBackend.setSort(mode)

    // Nothing searched yet: the recent queries are the useful thing to show, rather than an
    // instruction to go and do something.
    val showingHistory = searchState.query.isBlank() && !searchState.searching

    // The default page is somewhere to *go*, not a list of what was typed. It gives way to the
    // recent queries the moment the field is touched, because that is the moment the listener has
    // decided they are going to type something — and a page of coloured categories under a field
    // they are reaching for is in the way of the thing they came to do.
    val showingShelves = showingHistory && !fieldFocused

    // What has been played out of a search, so the page can offer the answer rather than the
    // question. Read here rather than remembered so it survives leaving the tab.
    val recentTracks by SonoraBackend.recentTracks.collectAsState()

    val backendState by SonoraBackend.state.collectAsState()

    var selectedTab by remember { mutableStateOf(SearchSource.YOUTUBE_MUSIC) }
    val showSoulseek = selectedTab == SearchSource.SOULSEEK

    // Results as the listener types. Restarting this coroutine on every keystroke *is* the
    // debounce: the delay only runs once typing has stopped. The backend drops anything that
    // arrives for a query the listener has already moved on from, so a slow answer cannot land
    // under a newer word.
    LaunchedEffect(query, selectedTab) {
        val term = query.trim()
        if (term.length < LIVE_SEARCH_MIN_CHARS) return@LaunchedEffect
        if (term == SonoraBackend.search.value.query) return@LaunchedEffect

        delay(LIVE_SEARCH_DEBOUNCE_MS)
        // The peer network is asked only when its own tab is showing: a broadcast search is not
        // something to send once per pause in typing while the listener is reading YouTube rows.
        SonoraBackend.searchWhileTyping(context, term, peers = selectedTab == SearchSource.SOULSEEK)
    }
    val showYoutube = selectedTab == SearchSource.YOUTUBE_MUSIC

    // The top result's second button, and the flow it opens. Held here rather than inside the
    // card: a sheet's host has to outlive the item that summoned it, or it is dismissed the moment
    // the list the card lives in is recomposed.
    var addTarget by remember { mutableStateOf<LibraryTrack?>(null) }
    val playlists by SonoraBackend.playlists.collectAsState()

    // Raised when a file was wanted and there was no session to ask for one. The sheet is asked
    // before the tab changes, because being moved to a sign-in form with nothing said is the same
    // as being moved there for no reason.
    var showConnect by remember { mutableStateOf(false) }

    // Asked once, before the first download. Where the files land decides whether they survive
    // uninstalling the app, so it is worth one question rather than a silent default.
    var askingWhere by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf<SearchHit?>(null) }

    ConnectToPeersSheet(
        open = showConnect,
        onConnect = {
            showConnect = false
            onNeedPeers()
        },
        onDismiss = { showConnect = false },
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

    val pickFolder = rememberLauncherForActivityResult(        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            SonoraBackend.setDownloadTree(context, uri.toString())
        }

        // Cancelling the picker cancels the download: the question is still unanswered, and the
        // tap is easy to repeat.
        if (uri != null) waiting?.let { SonoraBackend.download(context, it) }
        waiting = null
    }

    /**
     * Looks for one YouTube Music track on the peer network.
     *
     * The whole point of the two sources side by side: YouTube can play a track immediately but
     * only ever at its own bitrate, while the network may be holding a lossless rip of the same
     * recording. So the artist and title go back out as a network search, rather than a download
     * being offered for a stream — the two are different things and only one of them is a file.
     */
    fun peerSearch(track: YtmTrack) {
        // Peers only. A row's overflow asks for a copy that can be kept, and a YouTube row in that
        // list would be a stream sitting above the files that were wanted.
        if (!SonoraBackend.searchPeers(
                context,
                SearchQueries.forTrack(track.title, track.artist),
            )
        ) {
            showConnect = true
        }
    }

    fun startDownload(hit: SearchHit) {
        if (settings.downloadTreeUri == null && !settings.promptedForDownloadFolder) {
            waiting = hit
            askingWhere = true
        } else {
            SonoraBackend.download(context, hit)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Search",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        )

        SearchField(
            query = query,
            onQueryChange = { typed ->
                query = typed
                // Emptying the box is a request to go back, not just to stop typing: with the field
                // no longer keyed on the committed query, the old rows would otherwise sit under an
                // empty box with no way back to the recent searches.
                if (typed.isBlank() && searchState.query.isNotBlank()) SonoraBackend.clearSearch()
            },
            onSubmit = { SonoraBackend.search(context, query.trim()) },
            onFocusChange = { fieldFocused = it },
        )

        if (!showingHistory) {
            ChoicePillRow {
                ChoicePill(
                    label = "YouTube Music",
                    selected = selectedTab == SearchSource.YOUTUBE_MUSIC,
                    onClick = { selectedTab = SearchSource.YOUTUBE_MUSIC },
                )
                ChoicePill(
                    label = "Soulseek",
                    selected = selectedTab == SearchSource.SOULSEEK,
                    onClick = {
                        selectedTab = SearchSource.SOULSEEK
                        // The catalogue has been answering while the listener typed; the peers have
                        // not been asked yet, and a tab that opens onto "nothing on the network"
                        // under a query already typed reads as a broken search.
                        val term = query.trim()
                        // Only when the peers have not answered this term: a submitted search
                        // already asked them, and asking again would wipe the rows on screen,
                        // reset the sort and start another twenty-second broadcast.
                        val peersSilent = searchState.hits.isEmpty() && !searchState.searching
                        if (term.length >= LIVE_SEARCH_MIN_CHARS &&
                            searchState.query == term &&
                            peersSilent
                        ) {
                            SonoraBackend.searchPeers(
                                context,
                                term,
                                record = false,
                                youtubeLoading = false,
                            )
                        }
                    },
                )
            }
        }

        if (showSoulseek && !showingHistory && (searchState.hits.isNotEmpty() || searchState.searching)) {
            ChoicePillRow {
                SortMode.entries.forEach { mode ->
                    ChoicePill(
                        label = mode.label,
                        selected = sort == mode,
                        onClick = { onSort(mode) },
                    )
                }
            }
        }

        DownloadStatus(
            state = download,
            onCancel = { SonoraBackend.cancelDownload() },
            onCancelRemaining = { SonoraBackend.cancelPendingDownloads() },
        )

        statusNote(searchState, showSoulseek, showYoutube, !entities.isEmpty)?.let { Note(it) }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = listContentPadding(withMiniPlayer = playing),
        ) {
            // The tracks played out of a search come first, because they are what a listener
            // coming back to this page is most likely to want: the thing they already found, with
            // its own cover, one tap from playing again. The queries they typed are the fallback
            // for when there is nothing yet.
            if (showingHistory && !showingShelves && recentTracks.isNotEmpty()) {
                item(key = "recent-tracks") {
                    ShelfHeader(
                        title = "Recently played",
                        subtitle = "Found here, and still one tap away",
                    )
                }

                items(recentTracks, key = { it.key }) { recent ->
                    val track = LibraryTrack(
                        file = null,
                        title = recent.title,
                        artist = recent.artist,
                        album = recent.album,
                        size = 0,
                        remote = recent.toYtm(),
                        artworkUrl = recent.artworkUrl,
                    )

                    SongRow(
                        track = track,
                        onClick = { SonoraPlayer.play(context, track) },
                        meta = listOfNotNull(recent.artist).joinToString("  ·  "),
                        trailing = {
                            IconButton(
                                onClick = { SonoraBackend.forgetRecentTrack(context, recent.key) },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove ${recent.title}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }

                item(key = "recent-tracks-gap") { Spacer(Modifier.height(24.dp)) }
            }

            // The default page, before anyone has touched the field: YouTube Music's own shelves,
            // so there is somewhere to go rather than an instruction to go. Deliberately under the
            // field rather than instead of it — the field is still the way in, and a page that hid
            // it would be a page that has to be tapped twice to do anything.
            if (showingShelves) {
                item(key = "shelves") {
                    BrowseShelves(
                        shelves = shelves,
                        onChoose = { category ->
                            // Both: the page opens, and the shelf underneath it fills in behind
                            // so going back lands on something rather than on a gap.
                            SonoraBackend.loadCategory(category)
                            onOpenCategory(category)
                        },
                        onOpen = onOpenPlaylist,
                    )
                }
            }

            if (showingHistory && !showingShelves) {
                if (history.isEmpty()) {
                    if (recentTracks.isEmpty()) {
                        item { Note("Search to find music, on YouTube Music or the peer network.") }
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = PAGE_GUTTER, end = 8.dp, top = 8.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Recent searches",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            TextButton(
                                onClick = { SonoraBackend.clearSearchHistory(context) },
                            ) {
                                Text(
                                    text = "Clear all",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    items(history, key = { it }) { term ->
                        RecentSearchRow(
                            term = term,
                            onClick = { runSearch(term) },
                            onRemove = { SonoraBackend.removeSearchQuery(context, term) },
                        )
                    }
                }
            } else {
                // Registered even while empty. A row added to the top of a list that has already
                // been laid out makes the list keep what was on top in place, which pushes the new
                // row above the viewport — and this row always arrives after the search has begun.
                // YouTube Music first, because it is the one source that can be heard the moment it
                // is tapped: a stream resolves and plays, where a peer result is a file that has to
                // finish downloading before anything comes out of it.
                if (showYoutube && (searchState.youtube.isNotEmpty() || searchState.youtubeLoading)) {
                    // The best of these is promoted above the list it is also in. Only once there is
                    // something to promote: a card above an empty or still-loading section is a
                    // title with no cover beside a skeleton, which reads as a result that came back
                    // with nothing in it.
                    val topResult = searchState.youtube.firstOrNull()
                    if (topResult != null) {
                        item(key = "top-result") {
                            TopResultCard(
                                track = LibraryTrack.fromRemote(topResult),
                                onPlay = {
                                    val picked = LibraryTrack.fromRemote(topResult)
                                    SonoraPlayer.play(context, picked)
                                    SonoraBackend.recordRecentTrack(context, picked)
                                },
                                onAddToPlaylist = {
                                    addTarget = LibraryTrack.fromRemote(topResult)
                                },
                                onMore = { peerSearch(topResult) },
                            )
                        }
                    }

                // Albums and artists, in shelves of their own rather than one shelf of both.
                //
                // Two reasons, and the second is the one that bites. They answer separately and
                // arrive separately, and a row that is half one half the other is *inserted* in the
                // middle when the second half lands — a lazy list keeps what it was showing in
                // place, so the shelf quietly scrolls past its own contents and the first thing
                // the listener sees is whatever arrived second. Two shelves cannot shift each
                // other. And a heading over each is honest about what arrived.
                if (showYoutube && entities.artists.isNotEmpty()) {
                    item(key = "artists") {
                        EntityShelf(
                            title = "Artists",
                            // No source line: the pills above already say YouTube Music, and saying
                            // it again under every heading turns a label into a disclaimer.
                            subtitle = "Artists in the catalogue",
                            entities = entities.artists,
                            onOpen = onOpenArtist,
                        )
                    }
                }

                    if (showYoutube && entities.albums.isNotEmpty()) {
                    item(key = "albums") {
                        EntityShelf(
                            title = "Albums",
                            subtitle = "Releases in the catalogue",
                            entities = entities.albums,
                            onOpen = onOpenAlbum,
                        )
                    }
                }

                item(key = "youtube") {
                        ResultSectionHeader("Songs")
                    }

                    item(key = "youtube-note") {
                        Text(
                            text = "Plays now; search the network for a lossless copy",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(
                                start = PAGE_GUTTER,
                                end = PAGE_GUTTER,
                                bottom = 8.dp,
                            ),
                        )
                    }
                    // Placeholders for the wait, in place of a section header and nothing under it.
                    // This is a network search behind another one, and a header with an empty body
                    // underneath reads as a failure rather than as work in progress.
                    if (searchState.youtube.isEmpty()) {
                        songListSkeleton(count = 6, keyPrefix = "skeleton:ytm")
                    }

                    itemsIndexed(
                        searchState.youtube,
                        key = { _, it -> "ytm:" + it.videoId },
                    ) { index, track ->
                        val last = index == searchState.youtube.lastIndex
                        SongRow(
                            track = LibraryTrack.fromRemote(track),
                            onClick = {
                                // Remembered because the listener chose it, not because they typed
                                // it. The query is already in the history; what is missing from
                                // the history is the answer they picked.
                                val picked = LibraryTrack.fromRemote(track)
                                SonoraPlayer.play(context, picked)
                                SonoraBackend.recordRecentTrack(context, picked)
                            },
                            onMore = { peerSearch(track) },
                            meta = listOfNotNull(track.artist, track.album).joinToString("  ·  "),
                            divider = !last,
                        )
                    }
                }

                if (showSoulseek) {
                    // An empty page is indistinguishable from a broken one. The network answering
                    // with nothing and the network never having been set up are two different
                    // states, and each has a different thing to do about it.
                    if (searchState.hits.isEmpty() && !searchState.searching) {
                        item(key = "soulseek-empty") {
                            SoulseekEmpty(
                                query = query.trim(),
                                connected = backendState is BackendState.Connected,
                                onSignIn = onNeedPeers,
                            )
                        }
                    }
                    if (searchState.hits.isNotEmpty()) {
                        item(key = "soulseek-instruction") {
                            Text(
                                text = "Tap any song to download",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    horizontal = PAGE_GUTTER,
                                    vertical = 6.dp,
                                ),
                            )
                        }
                    }
                    items(searchState.hits, key = { it.peer + it.filename }) { hit ->
                        ResultRow(
                            hit = hit,
                            onDownload = { startDownload(hit) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }

    if (askingWhere) {
        AlertDialog(
            onDismissRequest = {
                askingWhere = false
                waiting = null
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Where should downloads go?") },
            text = {
                Text(
                    "Files Sonora creates in shared storage are deleted if you uninstall the " +
                        "app. Choose a folder and the files are yours to keep.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askingWhere = false
                        pickFolder.launch(null)
                    },
                ) {
                    Text("Choose folder")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        askingWhere = false
                        SonoraBackend.useDefaultDownloadFolder(context)
                        waiting?.let { SonoraBackend.download(context, it) }
                        waiting = null
                    },
                ) {
                    Text("Use default", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}

/**
 * The line under the controls, or null when there is nothing worth saying.
 *
 * It only ever describes the peer results, so it stays quiet while those are hidden. "No results"
 * especially has to mean no results from anywhere: said above a row of albums or artists it would
 * read as a flat contradiction.
 */
internal fun statusNote(
    state: SearchState,
    showSoulseek: Boolean,
    showYoutube: Boolean,
    albumsAndArtistsShown: Boolean,
): String? {
    val youtubeCount = state.youtube.size
    val anyResults = state.hits.isNotEmpty() || youtubeCount > 0 || albumsAndArtistsShown
    val stillLooking = state.searching || state.youtubeLoading
    val peerLine = "${state.matched} file(s) from ${state.peers} Soulseek peer(s)"
    val youtubeLine = "$youtubeCount on YouTube Music"

    return when {
        // Nothing has been searched for, so there is no result to describe.
        state.query.isBlank() -> null

        // Says nothing about counts while a source is still outstanding: half the numbers would be
        // final and half provisional, and a total that is quietly wrong is worse than none.
        stillLooking -> "Searching\u2026"

        !anyResults -> "No results for \u201c${state.query}\u201d."

        // Albums or artists are showing but neither playable source answered, so there is nothing
        // to count. Saying "0 on YouTube Music" over them would read as a result, not an absence.
        state.hits.isEmpty() && youtubeCount == 0 -> null

        showSoulseek && state.hits.isNotEmpty() && showYoutube -> "$youtubeLine \u00b7 $peerLine"

        showSoulseek && state.hits.isNotEmpty() -> peerLine

        showYoutube -> youtubeLine

        else -> null
    }
}

@Composable
private fun RecentSearchRow(term: String, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = PAGE_GUTTER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = term,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Remove ${term}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ResultRow(
    hit: SearchHit,
    onDownload: () -> Unit,
) {
    val cleanPath = hit.filename.replace('/', '\\')
    val rawExtension = cleanPath.substringAfterLast('.', "")
    val filetype = if (rawExtension.length in 2..5 && rawExtension.all { it.isLetterOrDigit() }) {
        rawExtension.uppercase()
    } else {
        "AUDIO"
    }

    val fileName = cleanPath.substringAfterLast('\\')
    val title = if (fileName.contains('.')) fileName.substringBeforeLast('.') else fileName
    val parentFolder = cleanPath.substringBeforeLast('\\', "").substringAfterLast('\\').takeIf { it.isNotBlank() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDownload)
            .padding(horizontal = PAGE_GUTTER, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = filetype,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            val details = listOfNotNull(
                parentFolder,
                quality(hit).ifEmpty { null },
                formatSize(hit.size),
                hit.peer,
            ).joinToString("  \u00b7  ")

            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(start = 12.dp),
        ) {
            Text(
                text = if (hit.hasFreeUploadSlot) "Ready" else "Queued ${hit.queueLength}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (hit.hasFreeUploadSlot) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                maxLines = 1,
            )
            hit.averageSpeed.takeIf { it > 0 }?.let {
                Text(
                    text = "${formatSize(it)}/s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceFaint,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DownloadStatus(
    state: DownloadState,
    onCancel: () -> Unit,
    onCancelRemaining: () -> Unit,
) {
    when (state) {
        DownloadState.Idle -> Unit

        is DownloadState.Downloading -> Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = buildString {
                        append("Downloading ${state.filename} \u2014 ")
                        append(
                            state.fraction?.let { "${(it * 100).toInt()}%" }
                                ?: formatSize(state.bytes),
                        )
                        if (state.remaining > 0) append("  \u00b7  ${state.remaining} queued")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                )

                if (state.remaining > 0) {
                    TextButton(onClick = onCancelRemaining) { Text("Cancel all") }
                }

                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Cancel download",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            LinearProgressIndicator(
                progress = { state.fraction ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is DownloadState.Completed -> Text(
            text = "Saved ${state.filename} (${formatSize(state.bytes)})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.accentText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        is DownloadState.Failed -> Text(
            text = "Download failed: ${state.reason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** Best-effort quality summary; which attributes exist depends on the format and the peer. */
private fun quality(hit: SearchHit): String {
    val attributes = hit.attributes

    val parts = buildList {
        attributes.bitrateKbps?.let { add("${it}kbps") }
        attributes.sampleRateHz?.let { add("${it / 1000}kHz") }
        attributes.bitDepth?.let { add("${it}bit") }
        attributes.durationSeconds?.let { add("${it / 60}:%02d".format(it % 60)) }
    }

    return parts.joinToString(" ")
}

/**
 * What the peer network is doing when it has no rows to show.
 *
 * Two states, not one: not signed in is a setup question with an answer, and signed in with no
 * hits is a search that found nothing. Showing the same blank page for both is what made this
 * screen look broken.
 */
@Composable
private fun SoulseekEmpty(query: String, connected: Boolean, onSignIn: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (connected) "Nothing on the network" else "Sign in to search the network",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (connected) {
                if (query.isEmpty()) {
                    "Search for a song and the network's files show up here."
                } else {
                    "No peer had “$query”. Try a different spelling, or search the catalogue."
                }
            } else {
                "Soulseek is where the lossless files are. Searching and streaming work without it."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!connected) {
            Button(onClick = onSignIn) { Text("Sign in") }
        }
    }
}

/** How long typing has to stop before the page asks again. */
private const val LIVE_SEARCH_DEBOUNCE_MS = 350L

/** The fewest characters worth asking about; the same threshold the backend holds. */
private const val LIVE_SEARCH_MIN_CHARS = 2
