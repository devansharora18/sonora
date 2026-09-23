package dev.sonora.backend

import kotlinx.serialization.Serializable

/**
 * The rules for the recently-played list.
 *
 * Pure, so the ordering and de-duplication are decided here and can be tested without a device.
 */
object PlayHistory {

    /** Long enough to hold a real listening history, short enough to stay a small document. */
    const val MAX = 50

    /**
     * Records a play as the most recent, returning the new list.
     *
     * Keyed by path because the filesystem is the library: a track *is* its file, so playing
     * something again moves it rather than adding a second copy. Without that, putting one album on
     * repeat would fill the list with that album and push out everything else.
     */
    fun record(history: List<PlayedTrack>, path: String, at: Long): List<PlayedTrack> {
        if (path.isBlank()) return history

        return (listOf(PlayedTrack(path, at)) + history.filterNot { it.path == path }).take(MAX)
    }
}

/** One track that was played, and when it started. */
@Serializable
data class PlayedTrack(val path: String, val playedAt: Long)
