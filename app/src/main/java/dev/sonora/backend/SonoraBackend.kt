package dev.sonora.backend

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import dev.sonora.protocol.DownloadOutcome
import dev.sonora.protocol.SoulseekSession
import dev.sonora.protocol.server.LoginResponse
import dev.sonora.service.SonoraService
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
import java.util.concurrent.ConcurrentHashMap

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

    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "aiff", "alac")

    private val WHITESPACE = Regex("\\s+")

    /** Anything that could act as a path separator, or that file systems dislike. */
    private val UNSAFE_FILENAME = Regex("[^A-Za-z0-9 ._()\\[\\]&'-]")

    private const val PLAYLISTS_FILE = "playlists.json"
    private const val MAX_FILENAME_LENGTH = 180
    private const val PROGRESS_POLL_MS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<BackendState>(BackendState.Idle)

    val state: StateFlow<BackendState> = _state.asStateFlow()

    private var session: SoulseekSession? = null

    private val _search = MutableStateFlow(SearchState())

    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)

    val download: StateFlow<DownloadState> = _download.asStateFlow()

    private val _library = MutableStateFlow<List<LibraryTrack>>(emptyList())

    val library: StateFlow<List<LibraryTrack>> = _library.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())

    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    /**
     * Rescans the download directory.
     *
     * The filesystem is the source of truth for what has been downloaded, so this is a scan
     * rather than a stored index — nothing to keep in sync, nothing to go stale. That holds until
     * a track is deleted outside the app; see PRD D5.
     */
    fun refreshLibrary(context: Context) {
        scope.launch {
            val directory = MusicDirectory.resolve(context).directory

            val files = directory.listFiles()
            Log.d(TAG, "library scan: ${directory.absolutePath} -> ${files?.size ?: -1} entry(s)")

            _library.value = files
                ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
                ?.sortedBy { it.name.lowercase() }
                ?.map { LibraryTrack.from(it, TagReader.read(it)) }
                .orEmpty()
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
                Playlists.addTrack(created, id, firstTrack.file.absolutePath)
            }
        }
    }

    fun renamePlaylist(context: Context, id: String, name: String) {
        editPlaylists(context) { Playlists.rename(it, id, name) }
    }

    fun deletePlaylist(context: Context, id: String) {
        editPlaylists(context) { Playlists.delete(it, id) }
    }

    fun addToPlaylist(context: Context, id: String, track: LibraryTrack) {
        editPlaylists(context) { Playlists.addTrack(it, id, track.file.absolutePath) }
    }

    fun removeFromPlaylist(context: Context, id: String, path: String) {
        editPlaylists(context) { Playlists.removeTrack(it, id, path) }
    }

    fun toggleLiked(context: Context, track: LibraryTrack) {
        editPlaylists(context) { Playlists.toggleLiked(it, track.file.absolutePath) }
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

    private fun store(context: Context) = PlaylistStore(File(context.filesDir, PLAYLISTS_FILE))

    /**
     * Downloads one search result into app-private storage.
     *
     * One at a time for now: concurrent transfers need their own queueing and progress story,
     * and a single download is what the flow needs to work first.
     */
    fun download(context: Context, hit: SearchHit) {
        val current = session ?: return
        if (_download.value is DownloadState.Downloading) return

        val directory = MusicDirectory.resolve(context).directory.apply { mkdirs() }
        val destination = destinationFor(directory, hit.filename)
        val name = destination.name

        _download.value = DownloadState.Downloading(
            filename = name,
            peer = hit.peer,
            bytes = 0,
            totalBytes = hit.size,
        )

        scope.launch {
            // The session reports no progress, so poll the file being written. Cheap, and it
            // avoids threading a callback through the transfer layer for a UI concern.
            val progress = launch {
                while (isActive) {
                    delay(PROGRESS_POLL_MS)
                    _download.update { state ->
                        if (state is DownloadState.Downloading) {
                            state.copy(bytes = destination.length())
                        } else {
                            state
                        }
                    }
                }
            }

            val outcome = current.download(hit.peer, hit.filename, destination, hit.size)
            progress.cancel()

            _download.value = when (outcome) {
                is DownloadOutcome.Completed ->
                    DownloadState.Completed(name, outcome.bytes, destination.absolutePath)

                is DownloadOutcome.Failed -> DownloadState.Failed(name, outcome.reason)
            }

            if (outcome is DownloadOutcome.Completed) {
                // Shared storage is scanned by the media provider, not by us: without this the file
                // exists but is invisible to every other player and to the system's own music apps.
                MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), null, null)
                refreshLibrary(context)
            }
        }
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
    fun search(query: String) {
        val current = session ?: return
        if (query.isBlank()) return

        val tokens = query.lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return

        val peersSeen = ConcurrentHashMap.newKeySet<String>()

        _search.value = SearchState(query = query, searching = true)
        Log.d(TAG, "searching: $query")

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
                    if (state.query != query) return@update state

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
            _search.update { if (it.query == query) it.copy(searching = false) else it }
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
    fun connect(context: Context, username: String, password: String) {
        if (_state.value == BackendState.Connecting || _state.value is BackendState.Connected) return

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
                onTrace = { Log.d(TAG, it) },
            )

            try {
                val response = newSession.connect()

                _state.value = when (response) {
                    is LoginResponse.Success -> {
                        session = newSession
                        BackendState.Connected(response.greeting)
                    }

                    is LoginResponse.Rejected -> {
                        newSession.close()
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
        session?.close()
        session = null
        _state.value = BackendState.Idle
    }
}
