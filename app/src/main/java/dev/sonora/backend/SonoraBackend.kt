package dev.sonora.backend

import android.content.Context
import android.util.Log
import dev.sonora.protocol.SoulseekSession
import dev.sonora.protocol.server.LoginResponse
import dev.sonora.service.SonoraService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<BackendState>(BackendState.Idle)

    val state: StateFlow<BackendState> = _state.asStateFlow()

    private var session: SoulseekSession? = null

    private val _search = MutableStateFlow(SearchState())

    val search: StateFlow<SearchState> = _search.asStateFlow()

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

        _search.value = SearchState(query = query, searching = true)
        Log.d(TAG, "searching: $query")

        scope.launch {
            // The socket write must not happen on the caller's thread.
            current.search(query) { response ->
                val audio = response.files.filter { isAudio(it.filename) }
                if (audio.isEmpty()) return@search

                val incoming = audio.map {
                    SearchHit(response.username, it.filename, it.size, it.attributes)
                }

                _search.update { state ->
                    // A response for an earlier query can still arrive; drop it.
                    if (state.query != query) return@update state

                    // Ranked for relevance rather than arrival order: peers answer in whatever
                    // order they like, so without this the first reply wins regardless of how
                    // well it matches. Deduped because a peer can send more than one response,
                    // and duplicate list keys would crash the UI.
                    val ranked = (state.hits + incoming)
                        .distinctBy { it.peer to it.filename }
                        .map { it to relevance(it, tokens) }
                        .filter { (_, score) -> score > 0 }
                        .sortedByDescending { (_, score) -> score }
                        .map { (hit, _) -> hit }
                        .take(MAX_RETAINED_HITS)

                    state.copy(hits = ranked, matched = ranked.size)
                }
            }

            delay(SEARCH_WINDOW_MS)
            _search.update { if (it.query == query) it.copy(searching = false) else it }
        }
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
