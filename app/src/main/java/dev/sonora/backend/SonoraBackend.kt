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

    /** Soulseek has no "search finished" signal; this is how long we call it still running. */
    private const val SEARCH_WINDOW_MS = 8_000L

    /** A broad query can match hundreds of thousands of files; keep the list bounded. */
    private const val MAX_RETAINED_HITS = 500

    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "aiff", "alac")

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

        _search.value = SearchState(query = query, searching = true)
        Log.d(TAG, "searching: $query")

        scope.launch {
            // The socket write must not happen on the caller's thread.
            current.search(query) { response ->
                val audio = response.files.filter { isAudio(it.filename) }
                if (audio.isEmpty()) return@search

                _search.update { state ->
                    // A response for an earlier query can still arrive; drop it.
                    if (state.query != query) return@update state

                    val hits = state.hits +
                        audio.map { SearchHit(response.username, it.filename, it.size, it.attributes) }

                    state.copy(
                        hits = hits.take(MAX_RETAINED_HITS),
                        matched = state.matched + audio.size,
                    )
                }
            }

            delay(SEARCH_WINDOW_MS)
            _search.update { if (it.query == query) it.copy(searching = false) else it }
        }
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
