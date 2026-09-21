package dev.sonora.backend

/** What the player is doing, as far as the UI is concerned. */
/** What happens when the queue runs out. */
enum class RepeatMode {
    /** Stop at the end. */
    Off,

    /** Loop the whole queue. */
    All,

    /** Loop the current track. */
    One,
}

data class PlaybackState(
    val track: LibraryTrack? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isShuffled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
)
