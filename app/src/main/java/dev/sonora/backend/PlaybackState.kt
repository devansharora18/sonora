package dev.sonora.backend

/** What the player is doing, as far as the UI is concerned. */
data class PlaybackState(
    val track: LibraryTrack? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isShuffled: Boolean = false,
)
