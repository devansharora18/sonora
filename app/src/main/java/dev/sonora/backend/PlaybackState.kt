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

/**
 * How far through the track playback is.
 *
 * Separate from [PlaybackState] because it is the one thing that changes several times a second,
 * and a page that subscribes to it should be the only thing redrawn when it does.
 */
data class Progress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

data class PlaybackState(
    val track: LibraryTrack? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val isShuffled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
    /**
     * Whether the track is known and its audio is still being fetched.
     *
     * A streaming track has no URL until one is minted for it, and that is a round trip to a
     * service that decides whether it will serve the track at all. Publishing the track first and
     * this flag with it is what lets the mini player appear on the tap rather than a second later
     * when the fetch lands — the listener gets the title, the cover and a spinner immediately, and
     * the sound when it is actually available.
     */
    val isResolving: Boolean = false,
    /**
     * Why this track is not playing, in a sentence.
     *
     * The track is kept rather than cleared, because a track that could not be resolved is still
     * the track the listener asked for: clearing it takes the player off the screen and leaves a
     * tap that did nothing at all, which is indistinguishable from a broken button. A player that
     * is still there and is saying *this* could not be fetched is a different thing entirely.
     */
    val problem: String? = null,
)
