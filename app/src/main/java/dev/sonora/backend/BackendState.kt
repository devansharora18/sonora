package dev.sonora.backend

/** What the local backend is doing, as far as the UI is concerned. */
sealed interface BackendState {

    data object Idle : BackendState

    data object Connecting : BackendState

    data class Connected(val greeting: String) : BackendState

    /** Login was refused, or the connection could not be established at all. */
    data class Failed(val reason: String) : BackendState
}
