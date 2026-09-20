package dev.sonora.backend

import android.content.Context
import android.util.Log
import dev.sonora.protocol.SoulseekSession
import dev.sonora.protocol.server.LoginResponse
import dev.sonora.service.SonoraService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<BackendState>(BackendState.Idle)

    val state: StateFlow<BackendState> = _state.asStateFlow()

    private var session: SoulseekSession? = null

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
