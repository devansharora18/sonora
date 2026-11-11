package dev.sonora.backend

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.sonora.backend.radio.RadioArtistDirectory
import dev.sonora.backend.radio.RadioBuilder
import dev.sonora.backend.radio.RadioSeed
import dev.sonora.backend.radio.RadioSetup
import dev.sonora.backend.radio.RadioState
import dev.sonora.backend.radio.SavedRadio
import dev.sonora.backend.radio.RadioStore
import dev.sonora.backend.taste.Origin
import dev.sonora.backend.taste.TasteEngine
import dev.sonora.backend.taste.TasteModel
import dev.sonora.backend.taste.TasteStore
import dev.sonora.backend.taste.TasteSummary
import dev.sonora.backend.taste.TasteTransfer
import dev.sonora.backend.taste.TrackRef
import dev.sonora.backend.taste.toLibraryTrack
import dev.sonora.backend.taste.toTrackRef
import dev.sonora.metadata.CoverArtCache
import dev.sonora.metadata.CoverArtTransport
import dev.sonora.metadata.Discovery
import dev.sonora.metadata.MetadataStore
import dev.sonora.metadata.MusicBrainzTransport
import dev.sonora.protocol.DownloadOutcome
import dev.sonora.protocol.SoulseekSession
import dev.sonora.protocol.server.LoginResponse
import dev.sonora.service.SonoraService
import dev.sonora.ytm.YtmBrowse
import dev.sonora.ytm.YtmCatalog
import dev.sonora.ytm.YtmCategory
import dev.sonora.ytm.YtmCatalogSearch
import dev.sonora.ytm.YtmRadio
import dev.sonora.ytm.YtmSearch
import dev.sonora.ytm.YtmShelves
import dev.sonora.ytm.YtmTrack
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Owns the Soulseek session and publishes its state to the UI.
 *
 * In-process by design (PRD D10): the UI and the protocol run in the same process, so this holds
 * the session directly rather than going over a local HTTP boundary. The foreground service's job
 * is to keep that process alive, not to broker calls.
 *
 * A process-wide singleton is enough while there is exactly one backend. If a second is ever
 * needed — a remote one for the §11 iOS path, say — replace this with an injected interface.
 */
object SonoraBackend {

    private const val TAG = "SonoraBackend"

    /**
     * Soulseek has no "search finished" signal. With hundreds of peers to contact, results keep
     * arriving for a while, so this is deliberately generous — claiming completion early is what
     * makes a search look like it found nothing.
     */
    private const val SEARCH_WINDOW_MS = 20_000L

    /** A broad query can match hundreds of thousands of files; keep the list bounded. */
    private const val MAX_RETAINED_HITS = 500

    /** The fewest characters worth asking the catalogue about while the listener is still typing. */
    private const val LIVE_SEARCH_MIN_CHARS = 2

    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "aiff", "alac")

    private val WHITESPACE = Regex("\\s+")

    /** Anything that could act as a path separator, or that file systems dislike. */
    private val UNSAFE_FILENAME = Regex("[^A-Za-z0-9 ._()\\[\\]&'-]")

    private const val PLAYLISTS_FILE = "playlists.json"
    private const val SAVED_TRACKS_FILE = "saved-tracks.json"
    private const val RECENT_TRACKS_FILE = "recent-tracks.json"
    private const val SETTINGS_FILE = "settings.json"
    private const val SEARCH_HISTORY_FILE = "searches.json"
    private const val PLAY_HISTORY_FILE = "plays.json"
    private const val TASTE_FILE = "taste.json"
    private const val RADIO_FILE = "radio.json"

    /** How many of the model's strongest artists the picker offers before a search. */
    private const val SUGGESTED_SEEDS = 8

    /** Synthetic plays from an import are spaced this far apart so no edge can form. */
    private const val SEED_SPACING_MS = 60L * 60L * 1000L

    /** A seeded track with no known length is recorded as a three-minute listen. */
    private const val SEED_DEFAULT_DURATION_MS = 180_000L

    private const val METADATA_CACHE_DIRECTORY = "metadata"
    private const val COVER_ART_CACHE_DIRECTORY = "covers"
    private const val MAX_FILENAME_LENGTH = 180
    private const val PROGRESS_POLL_MS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * For work that outlives any one screen — a lyrics fetch, say, which must finish even though
     * the listener closed the player while it was out.
     *
     * Its own scope rather than a composable's, because a request tied to a composition is
     * cancelled the moment that composition leaves, and a listener who opens the player to read the
     * words and swipes back has still asked for them.
     */
    val lyricsScope: CoroutineScope = scope

    private val _state = MutableStateFlow<BackendState>(BackendState.Idle)

    val state: StateFlow<BackendState> = _state.asStateFlow()

    private var session: SoulseekSession? = null

    private val _search = MutableStateFlow(SearchState())

    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)

    val download: StateFlow<DownloadState> = _download.asStateFlow()

    /** Results waiting their turn, in the order they were asked for. */
    private val pending = ConcurrentLinkedQueue<SearchHit>()

    /** True while a worker is draining [pending]. */
    @Volatile
    private var draining = false

    @Volatile
    private var activeScratch: File? = null

    @Volatile
    private var activeTarget: File? = null

    private val _library = MutableStateFlow<List<LibraryTrack>>(emptyList())

    val library: StateFlow<List<LibraryTrack>> = _library.asStateFlow()

    /**
     * Whether a library scan is in flight.
     *
     * A library that is empty because it is being read looks exactly like a library that is empty
     * because there is nothing in it, and the second reading is the one the listener acts on. The
     * scan reads a folder and the system's own index, so it is slow enough to be worth telling the
     * two apart.
     */
    private val _scanning = MutableStateFlow(false)

    /** Whether a scan is under way, so a second request for the same answer can drop out. */
    private val scanInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /**
     * YouTube Music tracks that have been kept but not downloaded.
     *
     * Held apart from [library] so a save does not have to wait for a filesystem scan, and so the
     * scan can be re-run without losing them: the folder is the truth about what is on the device,
     * and this is the truth about what has been kept.
     */
    private val _saved = MutableStateFlow<List<SavedTrack>>(emptyList())

    val saved: StateFlow<List<SavedTrack>> = _saved.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())

    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _settings = MutableStateFlow(Settings())

    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())

    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    /**
     * Tracks played out of a search, newest first.
     *
     * Held next to the query history rather than inside it because they answer different questions:
     * the strings are "what have I typed", these are "what did I pick". A search page that offers
     * back the words but not the tracks makes the listener find the same thing twice.
     */
    private val _recentTracks = MutableStateFlow<List<RecentTrack>>(emptyList())

    val recentTracks: StateFlow<List<RecentTrack>> = _recentTracks.asStateFlow()

    /**
     * Tracks that have been played, most recent first.
     *
     * Paths rather than tracks: the filesystem is the library, so what is remembered is which file
     * was played. One that has since been deleted simply drops out when the list is resolved
     * against the library.
     */
    private val _playHistory = MutableStateFlow<List<PlayedTrack>>(emptyList())

    val playHistory: StateFlow<List<PlayedTrack>> = _playHistory.asStateFlow()


    /**
     * What YouTube Music holds for an artist or an album, by browse id.
     *
     * A cache rather than state that is fetched per screen, because these pages are reached from
     * three different places — a search result, a library row's menu, the player — and a listener
     * who opens the same artist's page three times should pay for it once. It grows as they explore
     * and is dropped when the process is, which is the right lifetime for a page of somebody else's
     * catalogue.
     */
    private val _browsed = MutableStateFlow<Map<String, BrowsedPage>>(emptyMap())

    val browsed: StateFlow<Map<String, BrowsedPage>> = _browsed.asStateFlow()

    /**
     * YouTube Music's own shelves, for a page that would otherwise have nothing to show.
     *
     * Held apart from [browsed] and asked for separately, because they answer a different question:
     * that is for a page somebody chose, and these are for a page nobody chose because there was
     * nothing else to put on it. Asked for once and kept — a listener who has scrolled past a grid
     * of genres does not wait for it again on the other tab.
     */
    private val _shelves = MutableStateFlow(Shelves())
    val shelves: StateFlow<Shelves> = _shelves.asStateFlow()

    /**
     * A playlist somebody else made, by the id a shelf row carried.
     *
     * Held rather than returned because the page is reached from three shelves and re-read each
     * time it is opened, and a screen that has to wait for a network answer to know its own title
     * is a screen that flickers every time it comes back.
     */
    private val _remotePlaylists = MutableStateFlow<Map<String, List<LibraryTrack>>>(emptyMap())
    val remotePlaylists: StateFlow<Map<String, List<LibraryTrack>>> = _remotePlaylists.asStateFlow()

    /** A playlist's tracks, fetched once. Empty until they land, and empty if there are none. */
    fun loadRemotePlaylist(browseId: String) {
        if (browseId.isBlank() || _remotePlaylists.value.containsKey(browseId)) return

        scope.launch {
            val tracks = YtmSearch.parse(YtmBrowse.raw(browseId))
            Log.d(TAG, "playlist: $browseId -> ${tracks.size} track(s)")
            _remotePlaylists.update { it + (browseId to tracks.map { track -> LibraryTrack.fromRemote(track) }) }
        }
    }

    /**
     * Which sources the search screen shows.
     *
     * Held apart from [SearchState] so it outlives a search. It says what is worth looking at, not
     * what one query turned up, and a filter that reset itself on every search would be useless.
     */
    private val _searchSources = MutableStateFlow(SearchSource.entries.toSet())

    val searchSources: StateFlow<Set<SearchSource>> = _searchSources.asStateFlow()


    private var coverArt: CoverArtCache? = null

    /**
     * The tracks the last scan found on the device, without the kept streams folded in.
     *
     * Kept apart from [_library] because the two halves of the library cost wildly different
     * amounts to produce. Reading a scan means walking the download folder, reading the tags off
     * every file in it and asking the media provider about the rest of the device — seconds, on a
     * library of any size. Folding in the kept streams is a list operation over something already
     * in memory.
     *
     * So this holds the expensive answer and [_publishLibrary] builds the cheap one from it, and
     * anything that only changes what has been *kept* re-publishes without asking the disk anything.
     * That is the difference between a tap on a track taking a moment and taking long enough to
     * look like the app has hung.
     */
    private var scanned: List<LibraryTrack> = emptyList()

    /**
     * Puts the kept streams back on top of the last scan and publishes the library.
     *
     * Synchronous and cheap enough to call from a write: no filesystem, no provider, no tags.
     */
    private fun publishLibrary() {
        _library.value = scanned.withSaved(_saved.value).sortedBy { it.title.lowercase() }
    }

    /**
     * Rescans the download directory.
     *
     * The filesystem is the source of truth for what has been downloaded, so this is a scan
     * rather than a stored index — nothing to keep in sync, nothing to go stale. That holds until
     * a track is deleted outside the app; see PRD D5.
     *
     * Coalesced while one is under way, because a play, a like and a playlist addition all end here
     * and none of them changes what is on the disk: three walks of every file's tags to answer the
     * same question three times is not a rescan, it is a stall.
     */
    fun refreshLibrary(context: Context) {
        scope.launch {
            if (scanInFlight.getAndSet(true)) return@launch
            _scanning.value = true
            val location = MusicDirectory.resolve(context, _settings.value.downloadTreeUri)
            val directory = location.directory

            // Asked for first so a scanned file can be given the handle that actually opens it.
            // Enumerating a folder and being allowed to read what is in it are separate
            // permissions, and on a device with scoped storage only the second one is worth having.
            val contentUris = DeviceMusic.contentUrisByPath(context)

            val downloaded = directory.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
                ?.sortedBy { it.name.lowercase() }
                ?.map { file ->
                    LibraryTrack.from(file, TagReader.read(file))
                        .copy(contentUri = contentUris[file.absolutePath])
                }
                .orEmpty()

            // The folder scan comes first because a just-downloaded file is not in MediaStore yet:
            // registering it with the media scanner is asynchronous.
            val known = downloaded.mapTo(HashSet()) { it.key }

            // Everything else comes from the provider, which already has the tags. It is consulted
            // even when device music is off — filtered down to the download folder — because it is
            // the system's own index and does not depend on the app being able to enumerate that
            // folder itself.
            val root = directory.absolutePath + File.separator
            val fromProvider = DeviceMusic.list(context).filter { track ->
                track.key !in known &&
                    (_settings.value.includeDeviceMusic || track.key.startsWith(root))
            }

            scanned = downloaded + fromProvider
            _scanning.value = false
            scanInFlight.set(false)
            publishLibrary()

            Log.d(
                TAG,
                "library: ${_library.value.size} track(s), ${downloaded.size} from " +
                    "${directory.absolutePath} (shared=${location.shared})",
            )
        }
    }

    /**
     * Reads playlists back from disk.
     *
     * Called when the UI needs them rather than at construction, so the backend does not require a
     * Context to exist.
     */
    fun refreshPlaylists(context: Context) {
        scope.launch { _playlists.value = store(context).load() }
    }

    fun refreshSettings(context: Context) {
        scope.launch { _settings.value = settingsStore(context).load() }
    }

    fun refreshSearchHistory(context: Context) {
        scope.launch { _searchHistory.value = searchHistoryStore(context).load() }
    }

    /**
     * Reads the play history, and points the player at the recorder.
     *
     * The wiring belongs here rather than in the player: this is where the store lives, and the
     * callback needs a context that no screen is around to provide.
     */
    fun refreshPlayHistory(context: Context) {
        val appContext = context.applicationContext
        SonoraPlayer.onTrackStarted = { track -> recordPlay(appContext, track) }

        scope.launch { _playHistory.value = playHistoryStore(appContext).load() }
    }

    // ---- Taste engine -------------------------------------------------------------------------

    @Volatile
    private var tasteEngine: TasteEngine? = null

    private val _taste = MutableStateFlow(TasteModel())

    /** The learned model, for the Taste screen. Re-published after every change. */
    val tasteState: StateFlow<TasteModel> = _taste.asStateFlow()

    /**
     * The taste engine, built on first use.
     *
     * Lazy because it needs a Context for `filesDir`, and the backend deliberately exists without
     * one. The same instance is kept for the process so the model is read from disk once.
     */
    fun taste(context: Context): TasteEngine = tasteEngine ?: synchronized(this) {
        tasteEngine ?: TasteEngine(TasteStore(File(context.filesDir, TASTE_FILE)))
            .also { tasteEngine = it }
    }

    /** Reads the model off disk and publishes it, so the screen opens with real numbers. */
    fun refreshTaste(context: Context) {
        scope.launch { _taste.value = taste(context).snapshot() }
    }

    /**
     * Records one finished play.
     *
     * @param durationMs 0 when the player never knew the length.
     */
    suspend fun recordTaste(
        context: Context,
        track: LibraryTrack,
        listenedMs: Long,
        durationMs: Long,
        origin: Origin,
    ) {
        if (_settings.value.pauseLearning) return

        val engine = taste(context)
        engine.recordPlay(
            ref = track.toTrackRef(),
            listenedMs = listenedMs,
            durationMs = durationMs.takeIf { it > 0L },
            origin = origin,
            liked = track.key in Playlists.likedKeys(_playlists.value),
        )
        _taste.value = engine.snapshot()
    }

    /**
     * Autoplay's next picks, as things the player can open.
     *
     * The engine decides *what*; the mapping to a file or a stream decides *how*. A pick with a
     * local lossless file behind it is preferred there, so this never has to choose.
     */
    suspend fun autoplayNext(context: Context, cur: TrackRef?, count: Int): List<LibraryTrack> {
        // A queue that came from a mix keeps its mode when it runs low, which is what stops a
        // Discover mix turning into Autoplay four tracks after it starts.
        val radio = activeRadio
        if (radio != null) {
            // Only while the queue is still the mix. A track the listener started by hand means the
            // radio is over, and refilling with the mix would hijack their choice.
            if (cur == null || cur.key in activeRadioKeys) {
                val more = buildRadio(context, radio, count)
                if (more.isNotEmpty()) return more
            }
            activeRadio = null
            activeRadioKeys = emptySet()
            _activeRadioLabel.value = null
        }

        val engine = taste(context)
        // Scoring walks the model; the radio call inside `next` is non-blocking, so the whole pick
        // runs off the main thread and the refill never competes with a frame.
        val refs = withContext(Dispatchers.Default) {
            engine.next(cur, count) { seed ->
                val videoId = seed?.ytmId ?: cur?.ytmId ?: return@next emptyList()
                YtmRadio.related(videoId).map { it.toTrackRef() }
            }
        }
        return refs.mapNotNull { it.toLibraryTrack() }
    }

    // ---- Radio --------------------------------------------------------------------------------

    @Volatile
    private var radioStore: RadioStore? = null

    @Volatile
    private var radioDirectory: RadioArtistDirectory? = null

    @Volatile
    private var radioBuilder: RadioBuilder? = null

    private val _radio = MutableStateFlow(RadioState())

    /** The draft being edited and every saved radio. */
    val radio: StateFlow<RadioState> = _radio.asStateFlow()

    /** The mix a refill continues. Null once the queue is no longer the mix's. */
    @Volatile
    private var activeRadio: RadioSetup? = null

    private val _activeRadioLabel = MutableStateFlow<String?>(null)

    /** What the playing queue is a mix of, for the player to name it. Null when it is not one. */
    val activeRadioLabel: StateFlow<String?> = _activeRadioLabel.asStateFlow()

    /** What that mix queued, so a refill can tell whether the listener is still inside it. */
    @Volatile
    private var activeRadioKeys: Set<String> = emptySet()

    private fun radioStore(context: Context): RadioStore = radioStore ?: synchronized(this) {
        radioStore ?: RadioStore(File(context.filesDir, RADIO_FILE)).also { radioStore = it }
    }

    private fun radioDirectory(context: Context): RadioArtistDirectory =
        radioDirectory ?: synchronized(this) {
            radioDirectory ?: RadioArtistDirectory().also { radioDirectory = it }
        }

    /**
     * The builder, wired to the two network sources.
     *
     * Built once per process: the directory it holds caches every artist it has resolved, and a
     * refill that rebuilt it would pay for the same lookups again.
     */
    private fun radioBuilder(context: Context): RadioBuilder = radioBuilder ?: synchronized(this) {
        radioBuilder ?: run {
            val directory = radioDirectory(context)
            RadioBuilder(
                engine = taste(context),
                seedRadio = { seed ->
                    val videoId = directory.trackFor(seed)
                    if (videoId == null) {
                        emptyList()
                    } else {
                        YtmRadio.related(videoId).map { it.toTrackRef() }
                    }
                },
                relatedTo = { ref ->
                    val videoId = ref.ytmId
                    if (videoId == null) {
                        emptyList()
                    } else {
                        YtmRadio.related(videoId).map { it.toTrackRef() }
                    }
                },
                seedCatalogue = { seed ->
                    directory.tracksFor(seed).map { it.toTrackRef() }
                },
            ).also { radioBuilder = it }
        }
    }

    /** Reads the picked artists off disk and publishes them. */
    fun refreshRadio(context: Context) {
        scope.launch(Dispatchers.IO) { _radio.value = radioStore(context).load() }
    }

    /**
     * Adds a seed, then resolves its YouTube page in the background.
     *
     * The pick is usable the moment it is made — the mix can be built from the name alone — and
     * the id arrives behind it so the *next* mix costs no lookup. Resolving first would have made
     * adding an artist wait on the network.
     */
    fun addRadioSeed(context: Context, seed: RadioSeed) {
        persistDraft(context) { it.withSeed(seed) }
        if (seed.browseId != null) return

        scope.launch {
            val resolved = runCatching { radioDirectory(context).resolve(seed.name) }.getOrNull()
            if (resolved != null) persistDraft(context) { it.withResolved(resolved) }
        }
    }

    fun removeRadioSeed(context: Context, seed: RadioSeed) =
        persistDraft(context) { it.without(seed) }

    fun setRadioStop(context: Context, stop: Int) =
        persistDraft(context) { it.withStop(stop) }

    fun setRadioFamiliar(context: Context, familiarOnly: Boolean) =
        persistDraft(context) { it.copy(familiarOnly = familiarOnly) }

    private fun persistDraft(context: Context, edit: (RadioSetup) -> RadioSetup) {
        persistRadio(context, _radio.value.copy(draft = edit(_radio.value.draft)))
    }

    private fun persistRadio(context: Context, state: RadioState) {
        _radio.value = state
        scope.launch(Dispatchers.IO) { radioStore(context).save(state) }
    }

    /**
     * Keeps the draft as a radio, and returns it.
     *
     * Saving the same artists twice with different variety makes two radios on purpose: the mix is
     * the ingredients, and two settings of them are two things worth going back to.
     */
    fun saveRadio(context: Context, name: String = ""): SavedRadio? {
        val draft = _radio.value.draft
        if (!draft.isReady) return null

        val radio = SavedRadio(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { defaultRadioName(draft) },
            seeds = draft.seeds,
            stop = draft.stop,
            familiarOnly = draft.familiarOnly,
            createdAt = System.currentTimeMillis(),
        )
        persistRadio(context, _radio.value.withRadio(radio))
        return radio
    }

    fun deleteRadio(context: Context, id: String) =
        persistRadio(context, _radio.value.without(id))

    /** Loads a saved radio back into the draft, ready to tune or press play on. */
    fun editRadio(context: Context, id: String) {
        val radio = _radio.value.saved.firstOrNull { it.id == id } ?: return
        persistDraft(context) { radio.toDraft() }
    }

    /** The name a saved radio gets when the listener does not give it one. */
    fun defaultRadioName(setup: RadioSetup): String {
        val names = setup.seeds.take(2).joinToString(", ") { it.name }
        val rest = setup.seeds.size - 2
        return if (rest > 0) "$names +$rest" else names.ifBlank { "Radio" }
    }

    /**
     * Artists worth offering before anyone searches: the strongest ones the model knows.
     *
     * Resolved to their YouTube pages so a suggestion can be added with one tap and no second
     * request later. A name the catalogue does not know is dropped rather than shown as a pick that
     * would build nothing.
     */
    suspend fun radioSuggestions(context: Context, limit: Int = SUGGESTED_SEEDS): List<RadioSeed> {
        val model = taste(context).snapshot()
        val names = withContext(Dispatchers.Default) {
            TasteSummary.of(model, limit = limit).artists.map { it.name }
        }
        val directory = radioDirectory(context)
        return names.mapNotNull { runCatching { directory.resolve(it) }.getOrNull() }
    }

    /** Artist search for the picker. */
    suspend fun searchRadioArtists(context: Context, query: String): List<RadioSeed> {
        if (query.isBlank()) return emptyList()

        val found = runCatching { YtmCatalogSearch.artists(query) }.getOrDefault(emptyList())
        return found.map { RadioSeed(it.title, it.browseId, it.artworkUrl) }
    }

    /**
     * Builds the mix for [mode] and returns it ready to queue.
     *
     * Empty when nothing could be built — no artists picked, no network, or every seed silent —
     * which is the screen's cue to say so rather than start an empty player.
     */
    suspend fun startRadio(context: Context): List<LibraryTrack> =
        startRadio(context, _radio.value.draft)

    /** Plays a saved radio without disturbing the draft it was saved from. */
    suspend fun startSavedRadio(context: Context, id: String): List<LibraryTrack> {
        val radio = _radio.value.saved.firstOrNull { it.id == id } ?: return emptyList()
        return startRadio(context, radio.toDraft())
    }

    private suspend fun startRadio(context: Context, setup: RadioSetup): List<LibraryTrack> {
        val tracks = buildRadio(context, setup)
        if (tracks.isNotEmpty()) {
            activeRadio = setup
            activeRadioKeys = tracks.mapTo(HashSet()) { it.key }
            _activeRadioLabel.value =
                (if (setup.familiarOnly) "Familiar · " else "Radio · ") +
                setup.seeds.joinToString(", ") { it.name }
        }
        return tracks
    }

    private suspend fun buildRadio(
        context: Context,
        setup: RadioSetup,
        count: Int = RadioBuilder.MIX_SIZE,
    ): List<LibraryTrack> {
        if (!setup.isReady) return emptyList()

        val builder = radioBuilder(context)
        val library = withContext(Dispatchers.Default) { _library.value.map { it.toTrackRef() } }

        val refs = withContext(Dispatchers.Default) {
            builder.build(setup = setup, library = library, count = count)
        }
        return refs.mapNotNull { it.toLibraryTrack() }
    }

    /** Wipes the learned model. */
    fun resetTaste(context: Context) {
        scope.launch {
            val engine = taste(context)
            engine.reset()
            _taste.value = engine.snapshot()
        }
    }

    /**
     * Seeds the model from an imported playlist.
     *
     * Each track is recorded as a completed play by the user, spaced an hour apart so no transition
     * edge forms — an import says "these songs are mine", not "these songs play in this order", and
     * letting the sequence teach the graph would put a playlist's arbitrary ordering into Autoplay.
     * The spacing also keeps the import out of the recent window's way: the last synthetic play is
     * an hour old, so the next real play does not form an edge with it either.
     */
    fun seedTaste(context: Context, tracks: List<LibraryTrack>) {
        if (tracks.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            val engine = taste(context)
            val now = System.currentTimeMillis()
            val refs = tracks.map { it.toTrackRef() }.distinctBy { it.key }

            refs.forEachIndexed { index, ref ->
                val duration = ref.durationMs ?: SEED_DEFAULT_DURATION_MS
                engine.recordPlay(
                    ref = ref,
                    listenedMs = duration,
                    durationMs = duration,
                    origin = Origin.USER,
                    liked = false,
                    now = now - (refs.size - index).toLong() * SEED_SPACING_MS,
                )
            }
            _taste.value = engine.snapshot()
        }
    }

    /** Writes the model to a document the user picked, for backup or to move to another device. */
    fun exportTaste(context: Context, uri: Uri) {
        scope.launch {
            val text = TasteTransfer.encode(taste(context).snapshot())
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }.onFailure { Log.w(TAG, "could not export taste: ${it.message}") }
        }
    }

    /** Reads a document the user picked and replaces the model with it. */
    fun importTaste(context: Context, uri: Uri) {
        scope.launch {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull() ?: return@launch

            val imported = TasteTransfer.decode(text) ?: run {
                Log.w(TAG, "imported taste document did not parse")
                return@launch
            }

            val engine = taste(context)
            engine.replace(imported)
            _taste.value = engine.snapshot()
        }
    }

    /**
     * Writes any queued model before the process goes away.
     *
     * Blocking on an IO dispatcher on purpose: this is called from a service teardown, where a
     * fire-and-forget write would be cancelled along with the process.
     */
    fun flushTaste() {
        val engine = tasteEngine ?: return
        runBlocking(Dispatchers.IO) { engine.flush() }
    }

    fun setAutoplay(context: Context, enabled: Boolean) {
        updateSettings(context) { it.copy(autoplay = enabled) }
    }

    fun setPauseLearning(context: Context, enabled: Boolean) {
        updateSettings(context) { it.copy(pauseLearning = enabled) }
    }

    private fun updateSettings(context: Context, edit: (Settings) -> Settings) {
        scope.launch {
            val updated = edit(_settings.value)
            if (updated == _settings.value) return@launch

            settingsStore(context).save(updated)
            _settings.value = updated
        }
    }

    /**
     * Remembers that a track started.
     *
     * Called for every track that begins — a tap, next, shuffle, or the end of the one before — so
     * the list is what was listened to rather than what was tapped.
     */
    private fun recordPlay(context: Context, track: LibraryTrack) {
        // Kept before the history is written, so a streamed track exists as a library entry by the
        // time anything reads the history. Without it, a play of streamed music leaves a key and
        // nothing behind it: it appears in no shelf, and its album is not among "recently added".
        track.remote?.let { save(context, it) }

        scope.launch {
            val updated = PlayHistory.record(
                history = _playHistory.value,
                track = track,
                at = System.currentTimeMillis(),
            )
            if (updated == _playHistory.value) return@launch

            playHistoryStore(context).save(updated)
            _playHistory.value = updated
        }
    }

    /**
     * Drops the current search, leaving the recent queries to be shown in its place.
     *
     * The results go too: keeping them under an empty search box would leave no way back to the
     * history, which is the point of clearing it.
     */
    fun clearSearch() {
        _search.value = SearchState()
    }

    /**
     * Stores a preference and re-applies anything it affects.
     *
     * The library is rebuilt rather than filtered in place, because the setting decides what is
     * *read* — with device music off there is nothing to filter, only a query not to run.
     */
    fun setIncludeDeviceMusic(context: Context, enabled: Boolean) {
        scope.launch {
            val updated = _settings.value.copy(includeDeviceMusic = enabled)
            if (updated == _settings.value) return@launch

            settingsStore(context).save(updated)
            _settings.value = updated
            refreshLibrary(context)
        }
    }

    /** Records the folder the user picked for downloads, or null to go back to the default. */
    fun setDownloadTree(context: Context, uri: String?) {        scope.launch {
            val updated = _settings.value.copy(
                downloadTreeUri = uri,
                promptedForDownloadFolder = true,
            )
            if (updated == _settings.value) return@launch

            settingsStore(context).save(updated)
            _settings.value = updated
            refreshLibrary(context)
        }
    }

    /** Records the folder to reshare, or null to share the download folder instead. */
    fun setShareTree(context: Context, uri: String?) {
        scope.launch {
            val updated = _settings.value.copy(shareTreeUri = uri)
            if (updated == _settings.value) return@launch

            settingsStore(context).save(updated)
            _settings.value = updated
        }
    }

    /**
     * Records that the user declined a folder and wants the default location.
     *
     * Remembered so the question is asked once. It is a real answer, not a dismissal: the files
     * land somewhere that Android deletes along with the app.
     */
    fun useDefaultDownloadFolder(context: Context) {
        scope.launch {
            val updated = _settings.value.copy(promptedForDownloadFolder = true)
            if (updated == _settings.value) return@launch

            settingsStore(context).save(updated)
            _settings.value = updated
        }
    }

    /**
     * Creates a playlist, optionally with a first track already in it.
     *
     * The track is added in the same edit because the id is generated here: creating and then
     * adding would be two writes with a window where the playlist exists but is empty, and a
     * failure between them would leave it that way.
     */
    fun createPlaylist(context: Context, name: String, firstTrack: LibraryTrack? = null) {
        val id = UUID.randomUUID().toString()

        editPlaylists(context) { current ->
            val created = Playlists.create(current, name, id)

            // A blank name leaves `created` unchanged, so the add finds no such id and is a no-op.
            if (firstTrack == null) {
                created
            } else {
                Playlists.addTrack(created, id, firstTrack.key)
            }
        }
    }

    fun renamePlaylist(context: Context, id: String, name: String) {
        editPlaylists(context) { Playlists.rename(it, id, name) }
    }

    fun deletePlaylist(context: Context, id: String) {
        editPlaylists(context) { Playlists.delete(it, id) }
    }

    /**
     * Writes an imported playlist, and returns its id.
     *
     * The tracks are *kept* rather than downloaded: an import is a list of names matched to a
     * catalogue, and each row can then be played as a stream or fetched as a lossless file from
     * the network, which are two different decisions and neither of them is implied by importing.
     *
     * Batching is kept inside [Innertube.addToPlaylist] rather than here; this only decides that a
     * failure part-way leaves the tracks that did land rather than nothing.
     */
    suspend fun importSpotifyPlaylist(
        context: Context,
        title: String,
        tracks: List<LibraryTrack>,
    ): String? {
        val id = UUID.randomUUID().toString()
        val name = title.trim().ifBlank { "Imported from Spotify" }

        writePlaylists(context) { current ->
            val created = Playlists.create(current, name, id)
            tracks.fold(created) { list, track ->
                Playlists.addTrack(list, id, track.key)
            }
        }

        // Kept outside the edit above, which is one atomic write: a save that failed would otherwise
        // leave a playlist whose entries all point at nothing. All of them in one call, because
        // saving them one at a time is a read-modify-write per track and they race.
        saveAll(context, tracks.mapNotNull { it.remote })

        Log.d(TAG, "imported '${name}': ${tracks.size} track(s) as $id")
        return id
    }

    fun addToPlaylist(context: Context, id: String, track: LibraryTrack) {
        // A streaming track is also kept: a playlist entry for something the library has never heard
        // of would resolve to nothing on the next launch, because there is no file behind it.
        track.remote?.let { save(context, it, playlistIds = listOf(id)) }
        editPlaylists(context) { Playlists.addTrack(it, id, track.key) }
    }

    /**
     * Keeps a YouTube Music track in the library without downloading it.
     *
     * Re-saving a track that is already kept updates its metadata in place rather than adding a
     * second row, so a search that found the same recording twice leaves one track, not two that
     * play the same audio over each other.
     */
    /** Keeps one track. Fire-and-forget, because a single save cannot be lost to a race. */
    fun save(context: Context, track: YtmTrack, playlistIds: List<String> = emptyList()) {
        scope.launch { saveAll(context, listOf(track), playlistIds) }
    }

    /**
     * Keeps a batch of YouTube Music tracks, in one read-modify-write, and waits for it.
     *
     * Suspended rather than launched because the caller has something to say afterwards: an import
     * that reports thirty-six tracks added while the store is still empty is a claim it has not
     * earned, and a process death in that window leaves a playlist whose every entry resolves to
     * nothing.
     *
     * Under a lock, and in one pass, because the store is a whole document: two writes overlapping
     * both read the same list and the second discards the first. An import of thirty-six tracks
     * saved one at a time is thirty-six chances for that, and on a phone it wins every time — the
     * import reported thirty-six tracks added and the store held one.
     */
    private suspend fun saveAll(
        context: Context,
        tracks: List<YtmTrack>,
        playlistIds: List<String> = emptyList(),
    ) {
        if (tracks.isEmpty()) return

        withContext(Dispatchers.IO) {
            val at = System.currentTimeMillis()
            val written = savedWrites.withLock {
                // Folded in one at a time through [keep], so re-keeping a track refreshes its row in
                // place rather than adding a second one that plays the same audio over itself, and
                // so the cap is applied once at the end of the batch.
                val updated = tracks.fold(_saved.value) { saved, track -> keep(saved, track, at, playlistIds) }
                savedTrackStore(context).save(updated)
                _saved.value = updated
                updated.size
            }
            Log.d(TAG, "kept $written saved track(s)")
            // Re-published, not re-scanned: a kept track has no file, so the scan's answer is still
            // correct and re-reading every file's tags to add one row is the whole cost of the tap.
            publishLibrary()
        }
    }

    /**
     * Forgets a kept track, and forgets one the library no longer needs.
     *
     * A download cannot do this, and that is worth saying rather than leaving to be discovered: a
     * file that arrives from the network is identified by its filename and its peer, neither of
     * which knows anything about YouTube Music. So a kept stream and a downloaded copy of the same
     * song are two rows, and they stay two — which is honest about where the audio came from, and is
     * why the like is stored against the stream rather than against the file.
     */
    fun unsave(context: Context, track: LibraryTrack) {
        val videoId = track.remote?.videoId ?: return
        forget(context, videoId)
    }

    /** Drops one kept row, for a video id. The one place a row ever leaves the kept list. */
    private fun forget(context: Context, vararg videoIds: String) {
        scope.launch {
            savedWrites.withLock {
                val updated = _saved.value.filterNot { it.videoId in videoIds }
                if (updated == _saved.value) return@withLock

                savedTrackStore(context).save(updated)
                _saved.value = updated
            }
            publishLibrary()
        }
    }

    /**
     * Loads the kept tracks, then rescans, so the library has them.
     *
     * The rescan is here rather than at the call site because the two are separate coroutines and
     * nothing orders them: a scan that reads the kept list before it has loaded builds a library of
     * files only, and the streamed tracks do not appear until something else happens to rescan.
     */
    fun refreshSaved(context: Context) {
        scope.launch {
            _saved.value = savedTrackStore(context).load()
            refreshLibrary(context)
        }
    }

    /** Serialises every write to the saved-track store. See [saveAll]. */
    private val savedWrites = Mutex()

    /**
     * Takes a track out of a playlist, and stops counting it as something a playlist holds.
     *
     * The cap in [capped] protects playlist members, so a removal that left the membership behind
     * would keep a track out of the kept list for ever, for a playlist it is no longer in.
     */
    fun removeFromPlaylist(context: Context, id: String, key: String) {
        scope.launch {
            val videoId = key.removePrefix(LibraryTrack.REMOTE_PREFIX)
            if (videoId != key) {
                val updated = withoutPlaylist(_saved.value, videoId, id)
                if (updated != _saved.value) {
                    savedTrackStore(context).save(updated)
                    _saved.value = updated
                }
            }
        }
        editPlaylists(context) { Playlists.removeTrack(it, id, key) }
    }

    /**
     * Deletes a downloaded file from the device, reporting whether it worked.
     *
     * Rescans rather than dropping it from the list in place, so the library stays a reflection of
     * what is actually on disk. Playlists and likes need no cleanup: they store paths and resolve
     * them against the library, so a deleted file simply stops appearing in them.
     *
     * The result matters because this legitimately fails for music the user added themselves: the
     * app can read and play another app's media, but scoped storage will not let it delete it. The
     * caller has to say so rather than leave a button that appears to work.
     */
    fun deleteDownload(context: Context, track: LibraryTrack): Boolean {
        // Nothing on disk to delete. The UI only offers this for a downloaded track, and returning
        // false here keeps that promise true if it ever offers it for another kind by mistake.
        val file = track.file ?: return false
        val location = MusicDirectory.resolve(context, _settings.value.downloadTreeUri)

        val inChosenFolder = location.tree != null &&
            file.parentFile?.absolutePath == location.directory.absolutePath

        val deleted = if (inChosenFolder) {
            deleteViaTree(context, location.tree, file.name)
        } else {
            runCatching { file.delete() }.getOrDefault(false)
        }

        if (!deleted) return false

        // Nudges the media provider to drop its row for a file that is no longer there, instead of
        // leaving other players showing a track that cannot be opened.
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)

        refreshLibrary(context)
        return true
    }

    /**
     * Deletes through the folder the user granted.
     *
     * A file the document provider created belongs to the provider, not to Sonora, so
     * [File.delete] is refused — and that ownership is exactly what makes it survive an uninstall.
     * The tree grant is what gives the app the right to remove it.
     */
    private fun deleteViaTree(context: Context, tree: Uri, name: String): Boolean = runCatching {
        val document = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            "${DocumentsContract.getTreeDocumentId(tree)}/$name",
        )

        DocumentsContract.deleteDocument(context.contentResolver, document)
    }.getOrDefault(false)

    /**
     * Adds or removes a like.
     *
     * A streamed track is kept on the way past, exactly as it is for a playlist. A like is stored as
     * a key, and a key is not a track: without keeping it, liking something you are streaming adds
     * a reference to a track the library has never heard of, and Liked Songs comes back with a row
     * it cannot draw — which is the whole of what a like is supposed to be.
     */
    fun toggleLiked(context: Context, track: LibraryTrack) {
        track.remote?.let { save(context, it, playlistIds = listOf(Playlists.LIKED_ID)) }

        val nowLiked = track.key !in Playlists.likedKeys(_playlists.value)
        editPlaylists(context) { Playlists.toggleLiked(it, track.key) }

        // The taste model keeps the like too, so a like shapes what Autoplay proposes even for a
        // track that has never been played through.
        scope.launch {
            val engine = taste(context)
            engine.setLiked(track.toTrackRef().key, nowLiked)
            _taste.value = engine.snapshot()
        }
    }

    /**
     * Applies an edit and persists it.
     *
     * Disk first, then state: publishing before the write succeeds would leave the UI showing a
     * playlist that is not actually saved. A no-op edit is dropped here so a rejected name does
     * not cause a pointless write.
     */
    private fun editPlaylists(context: Context, edit: (List<Playlist>) -> List<Playlist>) {
        scope.launch {
            val updated = edit(_playlists.value)
            if (updated == _playlists.value) return@launch

            store(context).save(updated)
            _playlists.value = updated
        }
    }

    /**
     * The same edit as [editPlaylists], but waited on.
     *
     * An import reports the playlist it made, so the write has to have happened before the id is
     * returned — otherwise the caller navigates to a playlist that is not there yet, or never arrives.
     */
    private suspend fun writePlaylists(
        context: Context,
        edit: (List<Playlist>) -> List<Playlist>,
    ) = withContext(Dispatchers.IO) {
        val updated = edit(_playlists.value)
        if (updated == _playlists.value) return@withContext

        store(context).save(updated)
        _playlists.value = updated
    }

    private fun store(context: Context) = PlaylistStore(File(context.filesDir, PLAYLISTS_FILE))

    private fun savedTrackStore(context: Context) =
        SavedTrackStore(File(context.filesDir, SAVED_TRACKS_FILE))

    private fun recentTrackStore(context: Context) =
        RecentTrackStore(File(context.filesDir, RECENT_TRACKS_FILE))

    /**
     * Remembers a track that was played out of a search, so the search page can offer it again.
     *
     * De-duplicated by key, newest first. The same recording reached by two different searches is
     * one entry — keyed on the track rather than on the words, which is what makes that true.
     */
    fun recordRecentTrack(context: Context, track: LibraryTrack) {
        val recent = track.remote ?: return

        scope.launch {
            val updated = listOf(
                RecentTrack(
                    key = track.key,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    artworkUrl = recent.artworkUrl,
                    artistId = recent.artistId,
                    albumId = recent.albumId,
                ),
            ) + _recentTracks.value.filterNot { it.key == track.key }

            recentTrackStore(context).save(updated)
            _recentTracks.value = updated
        }
    }

    fun refreshRecentTracks(context: Context) {
        scope.launch { _recentTracks.value = recentTrackStore(context).load() }
    }

    /**
     * Forgets one track from the recently-played list.
     *
     * The list exists only to offer a track back, so removing a row removes the offer and nothing
     * else: the play history and the library are untouched.
     */
    fun forgetRecentTrack(context: Context, key: String) {
        scope.launch {
            val updated = _recentTracks.value.filterNot { it.key == key }
            if (updated == _recentTracks.value) return@launch

            recentTrackStore(context).save(updated)
            _recentTracks.value = updated
        }
    }

    private fun settingsStore(context: Context) =
        SettingsStore(File(context.filesDir, SETTINGS_FILE))

    private fun searchHistoryStore(context: Context) =
        SearchHistoryStore(File(context.filesDir, SEARCH_HISTORY_FILE))

    private fun playHistoryStore(context: Context) =
        PlayHistoryStore(File(context.filesDir, PLAY_HISTORY_FILE))

    /**
     * Cover art for a catalogue release, or null when it has none.
     *
     * Blocking, so callers hand it to a coroutine on the IO dispatcher: it may fetch, and every
     * answer is cached on disk, including "this release has no cover".
     */
    fun coverArt(context: Context, releaseGroupId: String): ImageBitmap? {
        val cache = coverArt ?: CoverArtCache(
            directory = File(context.filesDir, COVER_ART_CACHE_DIRECTORY),
            fetch = CoverArtTransport(onTrace = { Log.d(TAG, "coverart: $it") }),
        ).also { coverArt = it }

        val bytes = cache.load(releaseGroupId) ?: return null

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }


    /**
     * Queues a search result for download.
     *
     * Queued rather than refused when something is already transferring, so a bulk request can
     * hand over a batch and let it drain. Still one transfer at a time: parallel transfers need
     * their own slot accounting, and peers queue us anyway.
     */
    fun download(context: Context, hit: SearchHit) {
        if (session == null) return

        pending += hit
        if (draining) return

        draining = true
        scope.launch {
            try {
                while (true) {
                    val next = pending.poll() ?: break
                    transfer(context, next)
                }
            } finally {
                draining = false
            }
        }
    }

    /**
     * Cancels the active download and purges any partially downloaded file.
     * If [cancelRemaining] is true, also drops remaining queued downloads.
     */
    fun cancelDownload(cancelRemaining: Boolean = false) {
        if (cancelRemaining) {
            pending.clear()
        }

        session?.cancelActiveDownload()

        activeScratch?.let { if (it.exists()) it.delete() }
        activeTarget?.let { if (it.exists()) it.delete() }

        _download.value = DownloadState.Idle
    }

    /**
     * Drops everything still waiting to download.
     */
    fun cancelPendingDownloads() {
        pending.clear()

        _download.update { state ->
            if (state is DownloadState.Downloading) state.copy(remaining = 0) else state
        }
    }

    /** Runs one transfer to completion, reporting progress and the outcome. */
    private suspend fun transfer(context: Context, hit: SearchHit) {
        val current = session ?: return

        val location = MusicDirectory.resolve(context, _settings.value.downloadTreeUri)
        val directory = location.directory.apply { mkdirs() }
        val name = destinationFor(directory, hit.filename).name

        val scratch = if (location.tree != null) {
            File(context.cacheDir, name)
        } else {
            File(directory, name)
        }
        val target = File(directory, name)

        activeScratch = scratch
        activeTarget = target

        _download.value = DownloadState.Downloading(
            filename = name,
            peer = hit.peer,
            bytes = 0,
            totalBytes = hit.size,
            remaining = pending.size,
        )

        // The session reports no progress, so poll the file being written. Cheap, and it
        // avoids threading a callback through the transfer layer for a UI concern.
        val progress = scope.launch {
            while (isActive) {
                delay(PROGRESS_POLL_MS)
                _download.update { state ->
                    if (state is DownloadState.Downloading) {
                        state.copy(bytes = scratch.length(), remaining = pending.size)
                    } else {
                        state
                    }
                }
            }
        }

        val outcome = try {
            current.download(hit.peer, hit.filename, scratch, hit.size)
        } finally {
            activeScratch = null
            activeTarget = null
        }
        progress.cancel()

        if (outcome is DownloadOutcome.Failed && outcome.reason == "cancelled") {
            scratch.delete()
            target.delete()
            _download.value = DownloadState.Idle
            return
        }

        val published = if (outcome is DownloadOutcome.Completed && location.tree != null) {
            val moved = runCatching {
                copyIntoTree(context, location.tree, scratch, name)
            }.getOrNull()

            scratch.delete()
            moved != null
        } else {
            true
        }

        _download.value = when (outcome) {
            is DownloadOutcome.Failed -> DownloadState.Failed(name, outcome.reason)

            is DownloadOutcome.Completed -> if (published) {
                DownloadState.Completed(name, outcome.bytes, target.absolutePath)
            } else {
                DownloadState.Failed(name, "could not write to the chosen folder")
            }
        }

        if (outcome is DownloadOutcome.Completed && published) {
            // Shared storage is scanned by the media provider, not by us: without this the file
            // exists but is invisible to every other player and to the system's own music apps.
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)

            // What we share just changed, and the server is what tells other users. Left until
            // the next connect, a peer would still see the old — possibly zero — count and
            // refuse to upload to us.
            current.advertiseShares()

            refreshLibrary(context)
        }
    }

    /**
     * Copies a finished file into the user's chosen folder, letting the document provider create
     * it, and returns whether that worked.
     */
    private fun copyIntoTree(context: Context, tree: Uri, source: File, name: String): Uri? {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )

        val target = DocumentsContract.createDocument(resolver, parent, mimeOf(name), name)
            ?: return null

        resolver.openOutputStream(target)?.use { output ->
            source.inputStream().use { it.copyTo(output) }
        } ?: return null

        return target
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac", "alac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/x-wav"
        "flac" -> "audio/flac"
        "wma" -> "audio/x-ms-wma"
        "aiff", "aif" -> "audio/x-aiff"
        else -> "audio/mpeg"
    }

    /**
     * Builds a destination filename from a peer-supplied virtual path.
     *
     * Names come from strangers, so separators are stripped and the resolved path is checked
     * against the download directory — a name like `..` would otherwise write outside it.
     */
    private fun destinationFor(directory: File, virtualPath: String): File {
        val name = virtualPath
            .substringAfterLast('\\')
            .substringAfterLast('/')
            .replace(UNSAFE_FILENAME, "_")
            .trim()
            .take(MAX_FILENAME_LENGTH)
            .ifBlank { "download" }

        val candidate = File(directory, name)
        val root = directory.canonicalPath + File.separator

        return if (candidate.canonicalPath.startsWith(root)) candidate else File(directory, "download")
    }

    /**
     * Searches the network, streaming results into [search] as peers answer.
     *
     * Soulseek searches have no completion signal — peers simply stop replying — so [search] is
     * marked not-searching after a fixed window while late results keep being appended.
     */
    fun search(context: Context, query: String) {
        val term = query.trim()
        if (term.isEmpty()) return

        loadYoutube(term)
        searchPeers(context, term, record = true, youtubeLoading = true)
    }

    /**
     * The answer to what has been typed so far, while it is still being typed.
     *
     * Two things make this different from [search]. Nothing is written to the search history — a
     * keystroke is not a search the listener would want to see offered back to them — and the peer
     * network is only asked when [peers] is set, because a query broadcast to strangers is not
     * worth sending once per letter. The catalogue is cheap and is what the page is mostly made of.
     *
     * A term already searched is not searched again, so a slow typist pausing mid-word does not
     * re-ask for what is already on screen.
     */
    fun searchWhileTyping(context: Context, query: String, peers: Boolean) {
        val term = query.trim()
        if (term.length < LIVE_SEARCH_MIN_CHARS) return
        if (_search.value.query == term) return

        if (peers) {
            searchPeers(context, term, record = false, youtubeLoading = true)
        } else {
            // Set by hand because [searchPeers] is what normally does it, and it is not being
            // called: without this the page would still be showing the previous query's rows.
            _search.value = SearchState(query = term, youtubeLoading = true)
        }
        loadYoutube(term)
    }

    /**
     * Asks the peer network only, for a file rather than a stream.
     *
     * Separate from [search] because the two answer different questions. This one is what "get me a
     * lossless copy" means: the answer has to be a real file on somebody's disk, and a YouTube row
     * in the same list would be a stream that plays now and can never be kept — the opposite of
     * what the listener asked for, sitting above the files they wanted.
     *
     * Returns false when there is no session to ask, so the caller can say so rather than leave a
     * search box standing over a list that will never fill.
     */
    fun searchPeers(
        context: Context,
        query: String,
        record: Boolean = true,
        youtubeLoading: Boolean = false,
    ): Boolean {
        val term = query.trim()
        if (term.isEmpty()) return false

        val current = session
        if (current == null) {
            // Nothing to ask the network, so the catalogue answers on its own. A search that
            // returned nothing at all because the session is down would read as "no such music".
            _search.value = SearchState(
                query = term,
                searching = false,
                youtubeLoading = youtubeLoading,
            )
            return false
        }

        val tokens = term.lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false

        val peersSeen = ConcurrentHashMap.newKeySet<String>()

        _search.value = SearchState(
            query = term,
            searching = true,
            youtubeLoading = youtubeLoading,
        )
        Log.d(TAG, "searching peers: $term")

        if (record) recordSearch(context, term)

        scope.launch {
            // The socket write must not happen on the caller's thread.
            current.search(query) { response ->
                val audio = response.files.filter { isAudio(it.filename) }
                if (audio.isEmpty()) return@search

                peersSeen += response.username

                val incoming = audio.map {
                    SearchHit(
                        peer = response.username,
                        filename = it.filename,
                        size = it.size,
                        attributes = it.attributes,
                        averageSpeed = response.averageSpeed,
                        hasFreeUploadSlot = response.hasFreeUploadSlot,
                        queueLength = response.queueLength,
                    )
                }

                _search.update { state ->
                    // A response for an earlier query can still arrive; drop it.
                    if (state.query != term) return@update state

                    // Deduped because a peer can send more than one response, and duplicate list
                    // keys would crash the UI.
                    val candidates = (state.hits + incoming)
                        .distinctBy { it.peer to it.filename }
                        .filter { relevance(it, tokens) > 0 }

                    state.copy(
                        hits = order(candidates, tokens, state.sort).take(MAX_RETAINED_HITS),
                        matched = candidates.size,
                        peers = peersSeen.size,
                    )
                }
            }

            delay(SEARCH_WINDOW_MS)
            _search.update { if (it.query == term) it.copy(searching = false) else it }
        }

        return true
    }

    /**
     * Asks YouTube Music, and publishes the answer.
     *
     * Dropped when a newer search has started, for the same reason the Soulseek results are: an
     * answer that arrives late belongs to a query the user has already moved on from, and showing it
     * under the new one is worse than showing nothing.
     *
     * Not gated on the Soulseek session. This is a public catalogue over plain HTTP and has nothing
     * to do with the network, so a listener who is not connected still gets results — which is the
     * point of it being a separate source rather than a filter on the peer search.
     */
    /**
     * YouTube Music's whole answer to a query: songs, albums and artists.
     *
     * The songs come from the songs tab, which is a much better-behaved response than the mixed one
     * — consistent rows, and a continuation token that pages properly — so they are asked for
     * separately rather than dug out of the mixed response. The albums and artists have no such
     * tab, so they come from the mixed response. Two requests rather than one, because the
     * alternative is a songs list assembled out of the carousels.
     */
    private fun loadYoutube(query: String) {
        scope.launch {
            val tracks = YtmSearch.search(query)
            Log.d(TAG, "youtube: ${tracks.size} track(s) for $query")
            if (_search.value.query == query) {
                _search.update { it.copy(youtube = tracks) }
            }
        }

        // Albums and artists, each shown the moment its own answer lands rather than after the
        // slowest of the two. The pair is what ends the wait, since until both have looked there
        // may yet be a shelf to show.
        scope.launch {
            val albums = YtmCatalogSearch.albums(query)
            Log.d(TAG, "youtube: ${albums.size} album(s) for $query")
            if (_search.value.query == query) {
                _search.update { it.copy(entities = it.entities.copy(albums = albums)) }
            }
        }

        scope.launch {
            val artists = YtmCatalogSearch.artists(query)
            Log.d(TAG, "youtube: ${artists.size} artist(s) for $query")
            if (_search.value.query == query) {
                _search.update {
                    it.copy(entities = it.entities.copy(artists = artists), youtubeLoading = false)
                }
            }
        }
    }

    /**
     * Reads an artist's or an album's page off YouTube Music, once.
     *
     * Returns the cached page if there is one, so opening the same artist twice does not ask twice.
     * The caller watches [browsed] rather than waiting on this, because a screen that is going to
     * show placeholders anyway has nothing to gain from a suspending call.
     */
    /** The moods and genres, fetched once. */
    fun loadShelves() {
        if (_shelves.value.categories.isNotEmpty() || _shelves.value.loading) return

        _shelves.update { it.copy(loading = true) }
        scope.launch {
            val categories = YtmShelves.categories()
            Log.d(TAG, "shelves: ${categories.size} categor(ies)")
            _shelves.update { it.copy(categories = categories, loading = false) }

            // Only the one the page shows, and only if the caller has not said which. The grid's
            // own pictures ship with the app, so nothing is fetched for the grid itself.
            val first = _shelves.value.chosen ?: categories.firstOrNull()
            if (first != null) loadCategory(first)
        }
    }


    /**
     * The playlists behind one category, fetched once per category.
     *
     * Keyed on the title rather than the id because the id is the same for every category and the
     * differentiator is the `params` beside it — and a grid of forty buttons all sharing one id is
     * exactly why the key has to include which one was pressed.
     */
    fun loadCategory(category: YtmCategory) {
        if (category.title in _shelves.value.playlists) return
        _shelves.update { it.copy(chosen = category) }

        scope.launch {
            val playlists = YtmShelves.categoryPlaylists(category)
            Log.d(TAG, "shelves: '${category.title}' -> ${playlists.size} playlist(s)")
            _shelves.update { it.copy(playlists = it.playlists + (category.title to playlists)) }
        }
    }

    fun browse(browseId: String, kind: PageKind, name: String? = null, artist: String? = null, artworkUrl: String? = null) {
        if (browseId.isBlank() || _browsed.value.containsKey(browseId)) return

        scope.launch {
            // One request for the whole page, because asked plainly YouTube answers with all of
            // it: the header, the songs, and the albums underneath. A second call for the albums
            // would buy nothing and would make them arrive under a page that already claimed to
            // be finished.
            // An album's page states no credits of its own — its rows carry a title, a length and
            // a picture, and nothing else — so the album's own name and artist are handed in from
            // the row that was tapped. Without them every one of its tracks is thrown away.
            val page = YtmBrowse.page(
                browseId,
                albumName = name.takeIf { kind == PageKind.ALBUM },
                albumArtist = artist,
            )
            val resolvedPage = if (page.artworkUrl.isNullOrBlank() && !artworkUrl.isNullOrBlank()) {
                page.copy(artworkUrl = artworkUrl)
            } else {
                page
            }
            Log.d(
                TAG,
                "browse: $browseId -> ${resolvedPage.tracks.size} track(s), " +
                    "${resolvedPage.albums.size} album(s), ${resolvedPage.singles.size} single(s)",
            )
            _browsed.update { it + (browseId to BrowsedPage(kind, resolvedPage)) }
        }
    }

    fun browse(request: PageRequest) {
        val browseId = request.browseId
        if (!browseId.isNullOrBlank()) {
            browse(browseId, request.kind, request.name, request.artist, request.artworkUrl)
            return
        }
        val cacheKey = request.cacheKey
        if (request.name.isBlank() || _browsed.value.containsKey(cacheKey)) return

        scope.launch {
            val entity = when (request.kind) {
                PageKind.ARTIST -> YtmCatalogSearch.artists(request.name).firstOrNull()
                PageKind.ALBUM -> {
                    val query = listOfNotNull(request.name, request.artist).filter { it.isNotBlank() }.joinToString(" ")
                    YtmCatalogSearch.albums(query).firstOrNull()
                        ?: YtmCatalogSearch.albums(request.name).firstOrNull()
                }
            }
            val resolvedId = entity?.browseId
            if (resolvedId.isNullOrBlank()) {
                _browsed.update { it + (cacheKey to BrowsedPage(request.kind, YtmBrowse.Page())) }
                return@launch
            }
            val page = YtmBrowse.page(
                resolvedId,
                albumName = request.name.takeIf { request.kind == PageKind.ALBUM },
                albumArtist = request.artist,
            )
            val resolvedArtwork = page.artworkUrl ?: entity.artworkUrl ?: request.artworkUrl
            val resolvedPage = if (page.artworkUrl != resolvedArtwork) {
                page.copy(artworkUrl = resolvedArtwork)
            } else {
                page
            }
            val entry = BrowsedPage(request.kind, resolvedPage)
            _browsed.update { it + (resolvedId to entry) + (cacheKey to entry) }
        }
    }

    /**
     * An artist's tracks, as though they were in the library.
     *
     * Only offered once the page has actually arrived: a partial list that grows under the listener
     * while they read it is a worse thing than a page that says it is still loading, and the
     * difference is a screen that has to be able to say so.
     */
    fun browsedTracks(browseId: String): List<LibraryTrack>? =
        _browsed.value[browseId]?.page?.tracks?.map { LibraryTrack.fromRemote(it) }?.ifEmpty { null }

    /** Remembers a query so the search screen can offer it again. */    private fun recordSearch(context: Context, query: String) {
        editSearchHistory(context) { SearchHistory.record(it, query) }
    }

    /** Forgets one query. */
    fun removeSearchQuery(context: Context, query: String) {
        editSearchHistory(context) { SearchHistory.remove(it, query) }
    }

    /** Forgets every query. */
    fun clearSearchHistory(context: Context) {
        editSearchHistory(context) { emptyList() }
    }

    private fun editSearchHistory(context: Context, edit: (List<String>) -> List<String>) {
        scope.launch {
            val updated = edit(_searchHistory.value)
            if (updated == _searchHistory.value) return@launch

            searchHistoryStore(context).save(updated)
            _searchHistory.value = updated
        }
    }

    /** Changes the result ordering, re-sorting what has already arrived. */
    fun setSort(mode: SortMode) {
        _search.update { state ->
            if (state.sort == mode) return@update state

            val tokens = state.query.lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
            state.copy(sort = mode, hits = order(state.hits, tokens, mode))
        }
    }

    /**
     * Shows or hides one source on the search screen.
     *
     * A view filter rather than a search filter: the other source is still asked, because the
     * answer is cached and switching back should not mean waiting again.
     */
    fun setSearchSource(source: SearchSource, enabled: Boolean) {
        _searchSources.update { if (enabled) it + source else it - source }
    }

    private fun order(
        hits: List<SearchHit>,
        tokens: List<String>,
        mode: SortMode,
    ): List<SearchHit> = when (mode) {
        // Relevance needs the tokens, so this is the only mode that is not a plain key.
        SortMode.RELEVANCE -> hits.sortedByDescending { relevance(it, tokens) }

        // Unknown speeds sort last rather than first: zero means "not reported", not "slowest".
        SortMode.SPEED -> hits.sortedByDescending { if (it.averageSpeed > 0) it.averageSpeed else -1 }

        SortMode.AVAILABILITY -> hits.sortedWith(
            compareByDescending<SearchHit> { it.hasFreeUploadSlot }.thenBy { it.queueLength },
        )

        SortMode.QUALITY -> hits.sortedByDescending {
            (it.attributes.sampleRateHz ?: 0) * (it.attributes.bitDepth ?: 0) +
                (it.attributes.bitrateKbps ?: 0)
        }

        SortMode.SIZE -> hits.sortedByDescending { it.size }
    }

    /**
     * How well a hit answers the query.
     *
     * Soulseek matches against the whole virtual path, so a file inside an `Ocean Eyes/` folder
     * legitimately matches while its own name says something else. A name match still means more
     * than a folder match, hence the weighting.
     */
    private fun relevance(hit: SearchHit, tokens: List<String>): Int {
        val path = hit.filename.lowercase()
        val name = path.substringAfterLast('\\')

        return tokens.count { name.contains(it) } * 2 + tokens.count { path.contains(it) }
    }

    /** Only music is offered in results, matching what the app is for. */
    private fun isAudio(filename: String): Boolean {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return extension in AUDIO_EXTENSIONS
    }

    /**
     * Connects to the network, starting the foreground service that keeps the process alive.
     *
     * Blocking work happens on [scope]; progress and the outcome appear on [state].
     */
    fun connect(
        context: Context,
        username: String,
        password: String,
        /**
         * Whether to keep the login. Only ever honoured for one that works: a rejected password is
         * forgotten rather than saved, because it would fail the same way on every launch.
         */
        remember: Boolean = false,
    ) {
        if (_state.value == BackendState.Connecting || _state.value is BackendState.Connected) return

        Log.d(TAG, "connecting as $username (remember=$remember)")

        _state.value = BackendState.Connecting
        SonoraService.start(context)

        // Bringing files across from private storage is a background chore, not something to make a
        // download or a Library visit wait on. Nothing to do once the legacy folder is gone.
        scope.launch {
            if (MusicDirectory.migrate(context) > 0) refreshLibrary(context)
        }

        scope.launch {
            val newSession = SoulseekSession(
                username = username,
                password = password,
                // The download folder is also the share by default: Soulseek etiquette treats
                // advertising nothing as leeching, and the files are already there and already the
                // user's.
                shareDirectory = MusicDirectory.resolve(
                    context,
                    _settings.value.shareTreeUri ?: _settings.value.downloadTreeUri,
                ).directory,
                onTrace = { Log.d(TAG, it) },
                onServerLost = ::onServerLost,
            )

            try {
                val response = newSession.connect()

                _state.value = when (response) {
                    is LoginResponse.Success -> {
                        session = newSession
                        rememberLogin(context, username, password, remember)
                        BackendState.Connected(response.greeting)
                    }

                    is LoginResponse.Rejected -> {
                        newSession.close()

                        // A remembered login that has stopped working is worse than none: it would
                        // fail the same way, unattended, on every launch.
                        rememberLogin(context, username, password, remember = false)

                        BackendState.Failed(
                            response.detail?.let { "${response.reason}: $it" } ?: response.reason,
                        )
                    }
                }
            } catch (e: Exception) {
                newSession.close()
                _state.value = BackendState.Failed("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Closes the session and releases the foreground service. The UI's path. */
    fun disconnect(context: Context) {
        closeSession()
        SonoraService.stop(context)
    }

    /**
     * Closes the session without touching the service. Called from the service's own teardown,
     * where stopping it again would recurse.
     */
    fun onServiceDestroyed() {
        closeSession()
    }

    private fun closeSession() {
        // Queued transfers cannot proceed without a session, so they are dropped rather than left
        // to be silently skipped one at a time.
        pending.clear()

        session?.close()
        session = null
        _state.value = BackendState.Idle
    }

    /**
     * The server connection ended on its own: the network dropped, or the server closed an idle
     * session.
     *
     * Nothing can be sent or received afterwards, so the session is closed and the app returns to
     * the connect screen. Without this the app keeps looking connected while every request fails —
     * and the next write to the dead socket used to bring the process down.
     */
    private fun onServerLost() {
        scope.launch {
            // A session that was never adopted is not ours to tear down; connect() reports its own
            // failure. A session closed deliberately is silent, so this cannot arrive late and take
            // down the session that replaced it.
            if (session == null) return@launch

            Log.d(TAG, "server connection lost; closing the session")
            closeSession()
        }
    }

    /** The login the user asked to be remembered, or null. Blocking: it reads the Keystore. */
    fun savedLogin(context: Context): SavedCredentials? = CredentialStore(context).load()

    /** Remembers a login that worked, or forgets the one that did not. */
    private fun rememberLogin(context: Context, username: String, password: String, remember: Boolean) {
        val store = CredentialStore(context)

        scope.launch {
            if (remember) store.save(SavedCredentials(username, password)) else store.clear()
        }
    }
}
