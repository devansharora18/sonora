package dev.sonora.backend

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
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

    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "aiff", "alac")

    private val WHITESPACE = Regex("\\s+")

    /** Anything that could act as a path separator, or that file systems dislike. */
    private val UNSAFE_FILENAME = Regex("[^A-Za-z0-9 ._()\\[\\]&'-]")

    private const val PLAYLISTS_FILE = "playlists.json"
    private const val SETTINGS_FILE = "settings.json"
    private const val SEARCH_HISTORY_FILE = "searches.json"
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

    /** Results waiting their turn, in the order they were asked for. */
    private val pending = ConcurrentLinkedQueue<SearchHit>()

    /** True while a worker is draining [pending]. */
    @Volatile
    private var draining = false

    private val _library = MutableStateFlow<List<LibraryTrack>>(emptyList())

    val library: StateFlow<List<LibraryTrack>> = _library.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())

    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _settings = MutableStateFlow(Settings())

    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())

    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    /**
     * Rescans the download directory.
     *
     * The filesystem is the source of truth for what has been downloaded, so this is a scan
     * rather than a stored index — nothing to keep in sync, nothing to go stale. That holds until
     * a track is deleted outside the app; see PRD D5.
     */
    fun refreshLibrary(context: Context) {
        scope.launch {
            val location = MusicDirectory.resolve(context, _settings.value.downloadTreeUri)
            val directory = location.directory

            val downloaded = directory.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
                ?.sortedBy { it.name.lowercase() }
                ?.map { LibraryTrack.from(it, TagReader.read(it)) }
                .orEmpty()

            // The folder scan comes first because a just-downloaded file is not in MediaStore yet:
            // registering it with the media scanner is asynchronous.
            val known = downloaded.mapTo(HashSet()) { it.file.absolutePath }

            // Everything else comes from the provider, which already has the tags. It is consulted
            // even when device music is off — filtered down to the download folder — because it is
            // the system's own index and does not depend on the app being able to enumerate that
            // folder itself.
            val root = directory.absolutePath + File.separator
            val fromProvider = DeviceMusic.list(context).filter { track ->
                track.file.absolutePath !in known &&
                    (_settings.value.includeDeviceMusic || track.file.absolutePath.startsWith(root))
            }

            val library = (downloaded + fromProvider).sortedBy { it.title.lowercase() }
            _library.value = library

            Log.d(
                TAG,
                "library: ${library.size} track(s), ${downloaded.size} from " +
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
        val file = track.file
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

    private fun settingsStore(context: Context) =
        SettingsStore(File(context.filesDir, SETTINGS_FILE))

    private fun searchHistoryStore(context: Context) =
        SearchHistoryStore(File(context.filesDir, SEARCH_HISTORY_FILE))

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
     * Drops everything still waiting to download.
     *
     * The transfer already running is left alone: aborting it mid-stream means closing its socket,
     * and that connection belongs to the transfer layer. It finishes; nothing follows it.
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

        val outcome = current.download(hit.peer, hit.filename, scratch, hit.size)
        progress.cancel()

        val published = if (outcome is DownloadOutcome.Completed && location.tree != null) {
            val moved = runCatching {
                copyIntoTree(context, location.tree, scratch, name)
            }.getOrNull()

            scratch.delete()
            moved != null
        } else {
            true
        }

        val target = File(directory, name)

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
    fun search(context: Context, query: String) {        val current = session ?: return
        if (query.isBlank()) return

        val tokens = query.lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return

        val peersSeen = ConcurrentHashMap.newKeySet<String>()

        _search.value = SearchState(query = query, searching = true)
        Log.d(TAG, "searching: $query")

        recordSearch(context, query)

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

    /** Remembers a query so the search screen can offer it again. */
    private fun recordSearch(context: Context, query: String) {
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
                // The download folder is also the share by default: Soulseek etiquette treats
                // advertising nothing as leeching, and the files are already there and already the
                // user's.
                shareDirectory = MusicDirectory.resolve(
                    context,
                    _settings.value.shareTreeUri ?: _settings.value.downloadTreeUri,
                ).directory,
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
        // Queued transfers cannot proceed without a session, so they are dropped rather than left
        // to be silently skipped one at a time.
        pending.clear()

        session?.close()
        session = null
        _state.value = BackendState.Idle
    }
}
