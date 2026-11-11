package dev.sonora.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.ui.theme.accentText
import dev.sonora.backend.BackendState
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.Playlists
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.PageRequest
import dev.sonora.ytm.YtmCategory
import dev.sonora.ytm.YtmPlaylistRef
import dev.sonora.backend.PageKind
import dev.sonora.backend.radio.RadioSeed
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.HazeState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import dev.sonora.spotify.SpotifyImportRunner
import dev.sonora.backend.SonoraPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App root. Navigation and the feature graph hang off here.
 */
@Composable
fun SonoraApp() {
    val context = LocalContext.current
    val state by SonoraBackend.state.collectAsState()


    // One launcher for all of them: Android shows these one dialog at a time, so firing separate
    // requests in the same frame would silently drop all but the first.
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)

                // Below API 30 this is also what lets downloads land in the shared Music folder.
                // Refused, downloads fall back to app-private storage rather than failing.
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }

        permissions.launch(wanted.toTypedArray())
    }

    // A remembered login connects without being asked — that is the whole point of remembering it.
    // The screen below is the fallback for when it does not work.
    LaunchedEffect(Unit) {
        if (state !is BackendState.Idle) return@LaunchedEffect

        val saved = withContext(Dispatchers.IO) { SonoraBackend.savedLogin(context) }
        saved?.let { SonoraBackend.connect(context, it.username, it.password, remember = true) }
    }

    // The Soulseek session is not the app's front door.
    //
    // YouTube Music search, streaming and the Spotify import all work without a Soulseek account —
    // they are plain HTTPS to a public catalogue. Gating the whole app on a login to a file-sharing
    // network would mean a listener who only wants to search and listen to YouTube could not open
    // the app at all, and the network is where downloads come from rather than where music is found.
    //
    // So the tabs are drawn in every state, and connecting becomes one of them: what the network is
    // for, offered rather than required. The session's own state still decides what the Network tab
    // shows, so a failed or dropped connection is visible rather than swallowed.
    MainTabs(state = state)
}

private enum class MainTab(val label: String) {
    Home("Home"),
    Search("Search"),
    Library("Library"),
    Settings("Settings"),
    /**
     * The Soulseek network, and where a listener connects to it.
     *
     * A tab rather than a front door: the catalogue does not need an account, so the network is
     * where downloads come from rather than a condition for opening the app.
     */
    Network("Network"),
}

/** The bar's tabs, in the order they are drawn. Built once because the list never changes. */
private val tabs: List<BottomTab> = MainTab.entries.map { BottomTab(it.label, it.icon()) }

private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.Home -> Icons.Rounded.Home
    MainTab.Search -> Icons.Rounded.Search
    MainTab.Library -> Icons.AutoMirrored.Rounded.List
    MainTab.Settings -> Icons.Rounded.Settings
    MainTab.Network -> Icons.Rounded.CloudDownload
}


@Composable
private fun ConnectScreen(state: BackendState) {
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var keepLogin by remember { mutableStateOf(false) }

    // Filled in before the fields are read, so a login that is remembered does not have to be typed
    // again after a failure — which is the case this screen is actually reached in.
    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { SonoraBackend.savedLogin(context) }
            ?: return@LaunchedEffect

        username = saved.username
        password = saved.password
        keepLogin = true
    }

    // A page heading like every other tab's, so the wordmark up in the bar is not the only thing
    // naming this one. Left-aligned with the rest rather than centred under it, because a heading
    // that sits in the middle of a form looks like a label on the form.
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Network",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PAGE_GUTTER)
                .padding(bottom = listBottomPadding(withMiniPlayer = false)),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state) {
                BackendState.Idle -> {
                    Text(
                        text = "Sign in to find lossless files on the network. Searching and " +
                            "streaming work without it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )

                    PillTextField(
                        value = username,
                        onValueChange = { username = it },
                        placeholder = "Soulseek username",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )

                    PillTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "Password",
                        isPassword = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                if (username.isNotBlank() && password.isNotBlank()) {
                                    SonoraBackend.connect(
                                        context,
                                        username.trim(),
                                        password,
                                        keepLogin,
                                    )
                                }
                            },
                        ),
                    )

                    // A switch rather than a checkbox: this is a setting being left on, not a
                    // box being ticked, and a bare square in the middle of a dark page reads as
                    // an unstyled control.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .rowClickable(onClick = { keepLogin = !keepLogin })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Save login",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = "Encrypted on this device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        SettingSwitch(checked = keepLogin) { keepLogin = it }
                    }

                    Spacer(Modifier.height(4.dp))

                    Button(
                        onClick = {
                            SonoraBackend.connect(context, username.trim(), password, keepLogin)
                        },
                        enabled = username.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                    ) {
                        Text("Connect", style = MaterialTheme.typography.titleMedium)
                    }

                    Text(
                        text = "An unknown username is registered on first sign-in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                BackendState.Connecting -> MessageState(message = "Signing in to Soulseek\u2026")

                is BackendState.Failed -> MessageState(
                    message = "Could not connect: ${state.reason}",
                    actionLabel = "Back",
                    onAction = { SonoraBackend.disconnect(context) },
                )

                // Connected: the network is up, so this tab is a status page rather than a form. It is
                // deliberately not blank — a tab that goes empty the moment it stops being useful is
                // indistinguishable from a broken one.
                is BackendState.Connected -> MessageState(
                    message = state.greeting.ifBlank { "Signed in to Soulseek" } +
                        ". Downloads come from here; searching and streaming do not.",
                    actionLabel = "Disconnect",
                    onAction = { SonoraBackend.disconnect(context) },
                )
            }
        }
    }
}


/**
 * The app's tabs, whether or not the network is connected.
 *
 * The same drawing serves both cases: [state] says what the network session is doing, and the
 * Network tab shows it. What the network is for is one tab, not a gate in front of the app.
 */
@Composable
private fun MainTabs(state: BackendState) {
        // Held here rather than at the root: the tabs only need it once they are being drawn, and
        // the branch that decides whether to draw them has no Context to hand.
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        // The import's own state, remembered here so a run survives a tab switch and a reconnect.
        // The write half is handed in rather than reached for, because creating a playlist needs a
        // Context this object only has once the tabs are being drawn.
        val importRunner = remember {
            SpotifyImportRunner { draft, seed ->
                val id = SonoraBackend.importSpotifyPlaylist(context, draft.title, draft.matched)
                    ?: error("the import produced no playlist")
                if (seed) SonoraBackend.seedTaste(context, draft.matched)
                id
            }
        }

        // Home first: it is where resuming and finding new music both start.
        var tab by remember { mutableStateOf(MainTab.Home) }
        var playerOpen by remember { mutableStateOf(false) }
        var tasteOpen by remember { mutableStateOf(false) }
        var radioOpen by remember { mutableStateOf(false) }
        var addTarget by remember { mutableStateOf<LibraryTrack?>(null) }
        // The Spotify import is a sequence with its own state, so it outlives the screen that
        // opened it — a listener who switches tabs mid-import should not lose their progress.
        var spotifyImport by remember { mutableStateOf(false) }

        // Held here rather than inside the Library so a playlist card on Home can open it.
        var openPlaylistId by remember { mutableStateOf<String?>(null) }
        var openArtistName by remember { mutableStateOf<String?>(null) }
        var openAlbumName by remember { mutableStateOf<String?>(null) }
        // A page opened from somewhere that has YouTube Music's id for it — a search result, or the
        // artist line on the player. Held apart from the two names above because a name alone
        // cannot fetch an artist's songs, and following one is for the songs.
        var openPage by remember { mutableStateOf<PageRequest?>(null) }
    // Shelf navigation stacks scoped to each tab so sub-pages never bleed into or block other tabs.
    val homePages = remember { mutableStateListOf<ShelfPage>() }
    val searchPages = remember { mutableStateListOf<ShelfPage>() }
    val currentPages = when (tab) {
        MainTab.Home -> homePages
        MainTab.Search -> searchPages
        else -> null
    }
    val remotePlaylists by SonoraBackend.remotePlaylists.collectAsState()
    val shelves by SonoraBackend.shelves.collectAsState()
        val playback by SonoraPlayer.state.collectAsState()
        val playlists by SonoraBackend.playlists.collectAsState()
        val likedKeys = remember(playlists) { Playlists.likedKeys(playlists) }

        // Binds to the playback service once the app is in use, so the first tap on a track
        // is not waiting on a connection.
        LaunchedEffect(Unit) { SonoraPlayer.connect(context) }

        // Playlists outlive the session, so they are read once when the app is usable rather
        // than on every visit to the Library.
        LaunchedEffect(Unit) { SonoraBackend.refreshPlaylists(context) }
        LaunchedEffect(Unit) { SonoraBackend.refreshSettings(context) }
        LaunchedEffect(Unit) { SonoraBackend.refreshSearchHistory(context) }
        LaunchedEffect(Unit) { SonoraBackend.refreshPlayHistory(context) }
        LaunchedEffect(Unit) { SonoraBackend.refreshSaved(context) }
        LaunchedEffect(Unit) { SonoraBackend.refreshRecentTracks(context) }
        LaunchedEffect(Unit) { SonoraBackend.refreshTaste(context) }

        LaunchedEffect(playback.track, playback.isPlaying) {
            while (playback.track != null) {
                SonoraPlayer.syncPosition()
                delay(500)
            }
        }

    // What the bar says and whether it offers a way back. Both come from what is open rather than
    // from the tab: a playlist opened from Home is not a tab, and its own name is the only title
    // that means anything while it is being read.
    // One Haze state for the whole page. The bars and the top bar all sample the same source, which
    // is what lets a cover scrolling past show through the mini player and the top bar in the same
    // frame — two states would sample two different snapshots and the page would tear between them.
    val hazeState = remember { HazeState() }

    Box(modifier = Modifier.fillMaxSize()) {
        // The page is the blur source. Every frosted surface in the app samples this subtree, so
        // anything drawn outside it is invisible to the glass.
        Box(modifier = Modifier.fillMaxSize().hazeSource(hazeState)) {
            // Inset by the status bar only. There is no bar across the top: every page carries a
            // large heading that says what it is, and a second name above it was a smaller copy
            // of that heading which disagreed with it the moment the heading scrolled away.
            when (tab) {
                MainTab.Home -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {
                    HomeScreen(
                        onOpenRadio = { radioOpen = true },
                        onImportSpotify = { spotifyImport = true },
                        onOpenShelfPlaylist = { homePages += ShelfPage.Playlist(it) },
                        onOpenCategory = { homePages += ShelfPage.Category(it) },
                        onRunSearch = { term ->
                            // A suggestion on Home is really a pre-filled search, so this is
                            // the whole action: go to Search and run it.
                            tab = MainTab.Search
                            SonoraBackend.search(context, term)
                        },
                        onOpenPlaylist = { id ->
                            // The same handoff in the other direction: the playlist is shown by
                            // the Library, so go there and ask it for that one.
                            openPlaylistId = id
                            tab = MainTab.Library
                        },
                    )
                }
                MainTab.Search -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {
                    SearchScreen(
                        onNeedPeers = { tab = MainTab.Network },
                        // The whole row, so the page opens with the cover the shelf was showing rather
                        // than a gap where it should be.
                        onOpenPlaylist = { searchPages += ShelfPage.Playlist(it) },
                        onOpenCategory = { searchPages += ShelfPage.Category(it) },
                        onOpenAlbum = { entity ->
                            openPage = PageRequest(
                                name = entity.title,
                                browseId = entity.browseId,
                                kind = PageKind.ALBUM,
                                // The second line of an album's card is its artist, and an album's page
                                // repeats it on none of its tracks.
                                artist = entity.subtitle,
                                artworkUrl = entity.artworkUrl,
                            )
                            tab = MainTab.Library
                        },
                        onOpenArtist = { entity ->
                            openPage = PageRequest(
                                name = entity.title,
                                browseId = entity.browseId,
                                kind = PageKind.ARTIST,
                            )
                            tab = MainTab.Library
                        },
                    )
                }

                MainTab.Library -> LibraryScreen(
                    onRunSearch = { term ->
                        tab = MainTab.Search
                        SonoraBackend.search(context, term)
                    },
                    openPlaylistId = openPlaylistId,
                    onOpenPlaylist = { openPlaylistId = it },
                    onClosePlaylist = { openPlaylistId = null },
                    openArtistName = openArtistName,
                    onCloseArtist = { openArtistName = null },
                    openAlbumName = openAlbumName,
                    onCloseAlbum = { openAlbumName = null },
                    openPage = openPage,
                    onClosePage = { openPage = null },
                    onNeedPeers = { tab = MainTab.Network },
                    onStartRadio = { artist ->
                        // A radio from one track is a radio from its artist: the mix is built from
                        // artists, and the artist is the only part of the row that could seed one.
                        SonoraBackend.addRadioSeed(context, RadioSeed(artist))
                        radioOpen = true
                    },
                )
                MainTab.Settings -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {
                    SettingsScreen(onOpenTaste = { tasteOpen = true })
                }

                // The connect screen, reachable at any time, and showing the session once
                // there is one.
                MainTab.Network -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {
                    ConnectScreen(state = state)
                }
            }
        }

        // The shelves' own pages, as one stack over the tab that opened them.
        if (currentPages != null && currentPages.isNotEmpty()) {
            BackHandler(enabled = true) { currentPages.removeAt(currentPages.lastIndex) }
        } else if (tab != MainTab.Home) {
            // Tapping system back on any secondary tab returns to Home before exiting.
            BackHandler(enabled = true) { tab = MainTab.Home }
        }

        when (val top = currentPages?.lastOrNull()) {
            is ShelfPage.Category -> {
                val category = top.category
                LaunchedEffect(category.title) { SonoraBackend.loadCategory(category) }

                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding(),
                ) {
                    CategoryScreen(
                        category = category,
                        playlists = shelves.playlists[category.title].orEmpty(),
                        onBack = { currentPages.removeAt(currentPages.lastIndex) },
                        onOpen = { playlist -> currentPages += ShelfPage.Playlist(playlist) },
                    )
                }
            }

            is ShelfPage.Playlist -> {
                val playlist = top.playlist
                LaunchedEffect(playlist.browseId) {
                    SonoraBackend.loadRemotePlaylist(playlist.browseId)
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        // Opaque, because a page drawn over a page without it is two pages
                        // showing through each other.
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding(),
                ) {
                    RemotePlaylistScreen(
                        playlist = playlist,
                        tracks = remotePlaylists[playlist.browseId].orEmpty(),
                        onBack = { currentPages.removeAt(currentPages.lastIndex) },
                        onPlayFrom = { index ->
                            val queue = remotePlaylists[playlist.browseId].orEmpty()
                            if (index < queue.size) SonoraPlayer.play(context, queue, index)
                        },
                    )
                }
            }

            null -> Unit
        }

        // The floor the bars stand on, drawn over the page and under everything else. Without it a
        // track row scrolling past the tab bar is still fully opaque right up to the pill's edge,
        // which is what makes a floating bar look pasted on rather than sitting in the page.
        BottomFadeScrim(
            modifier = Modifier.align(Alignment.BottomCenter),
            withMiniPlayer = playback.track != null,
        )

        // The bars, bottom-anchored in one column so they share an edge and a width cap. Two
        // independently-sized bars would not line up on a wide screen.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val track = playback.track
            if (track != null) {
                MiniPlayer(
                    track = track,
                    isPlaying = playback.isPlaying,
                    isResolving = playback.isResolving,
                    onPlayPause = { SonoraPlayer.togglePlayPause() },
                    onNext = { SonoraPlayer.next() },
                    onPrevious = { SonoraPlayer.previous() },
                    onExpand = { playerOpen = true },
                    hazeState = hazeState,
                )
                Spacer(Modifier.height(8.dp))
            }

            FloatingTabBar(
                tabs = remember { tabs },
                selectedIndex = MainTab.entries.indexOf(tab),
                onTabSelected = { index ->
                    val entry = MainTab.entries[index]
                    if (tab == entry) {
                        // Tapping the active tab resets it to root.
                        when (entry) {
                            MainTab.Home -> homePages.clear()
                            MainTab.Search -> {
                                if (searchPages.isNotEmpty()) {
                                    searchPages.clear()
                                } else {
                                    SonoraBackend.clearSearch()
                                }
                            }
                            MainTab.Library -> {
                                openPlaylistId = null
                                openArtistName = null
                                openAlbumName = null
                                openPage = null
                            }
                            else -> Unit
                        }
                    } else {
                        // Leaving a tab resets sub-views so coming back or switching tabs is clean.
                        if (entry != MainTab.Library) {
                            openPlaylistId = null
                            openArtistName = null
                            openAlbumName = null
                            openPage = null
                        }
                        tab = entry
                    }
                },
                hazeState = hazeState,
            )
        }

            AnimatedVisibility(
                visible = playerOpen,
                enter = slideInVertically(
                    initialOffsetY = { fullHeight -> fullHeight },
                    animationSpec = tween(
                        durationMillis = 240,
                        easing = CubicBezierEasing(0.1f, 1f, 0.1f, 1f),
                    ),
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 180),
                ),
                exit = slideOutVertically(
                    targetOffsetY = { fullHeight -> fullHeight },
                    animationSpec = tween(
                        durationMillis = 200,
                        easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f),
                    ),
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 160),
                ),
            ) {
                BackHandler { playerOpen = false }
                NowPlayingScreen(
                    context = context,
                    onClose = { playerOpen = false },
                    isLiked = playback.track?.let { it.key in likedKeys } == true,
                    onToggleLike = {
                        playback.track?.let { SonoraBackend.toggleLiked(context, it) }
                    },
                    onToggleShuffle = { SonoraPlayer.toggleShuffle() },
                    onCycleRepeat = { SonoraPlayer.cycleRepeat() },
                    onAddToPlaylist = { addTarget = playback.track },
                    onOpenArtist = { artistName ->
                        openArtistName = null
                        openAlbumName = null
                        openPlaylistId = null
                        openPage = PageRequest(
                            name = artistName,
                            browseId = playback.track?.remote?.artistId,
                            kind = PageKind.ARTIST,
                        )
                        tab = MainTab.Library
                        playerOpen = false
                    },
                    onNeedPeers = { tab = MainTab.Network },
                    onOpenAlbum = { albumName ->
                        openAlbumName = null
                        openArtistName = null
                        openPlaylistId = null
                        openPage = PageRequest(
                            name = albumName,
                            browseId = playback.track?.remote?.albumId,
                            kind = PageKind.ALBUM,
                            artist = playback.track?.artist ?: playback.track?.remote?.artist,
                            artworkUrl = playback.track?.artworkUrl ?: playback.track?.remote?.artworkUrl,
                        )
                        tab = MainTab.Library
                        playerOpen = false
                    },
                )
            }
        }

        if (radioOpen) {
            BackHandler { radioOpen = false }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding(),
            ) {
                RadioScreen(
                    onBack = { radioOpen = false },
                    onPlay = { _, tracks ->
                        // The mix replaces the queue: it is a thing to listen to now, not something
                        // to append to whatever was already there.
                        SonoraPlayer.play(context, tracks, 0)
                        radioOpen = false
                    },
                )
            }
        }

        if (tasteOpen) {
            BackHandler { tasteOpen = false }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding(),
            ) {
                TasteScreen(onBack = { tasteOpen = false })
            }
        }

        if (spotifyImport) {
            SpotifyImportSheet(
                state = importRunner.state.collectAsState().value,
                onStart = { link -> importRunner.start(scope, link) },
                onConfirm = { draft, seed ->
                    scope.launch { importRunner.confirm(draft, seed) }
                },
                onDismiss = {
                    spotifyImport = false
                    importRunner.dismiss()
                },
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
    }
