package dev.sonora.backend

/**
 * The rules for editing playlists, as pure functions.
 *
 * Kept separate from [SonoraBackend] so the behaviour can be tested without a Context or a
 * filesystem — the same reason the protocol layer sits outside the service. Persisting the result
 * is the caller's job.
 *
 * An edit that changes nothing comes back structurally equal to its input, which is how the caller
 * tells a no-op from a real change and skips the write.
 */
object Playlists {

    /**
     * Reserved id and name for the liked-songs list.
     *
     * Liked songs is a playlist rather than a second store, because a like is add/remove on a list
     * of paths — which is exactly what a playlist already is. That reuses the storage, the editing
     * rules and the playback queue instead of duplicating three things to express one flag.
     */
    const val LIKED_ID = "liked-songs"
    const val LIKED_NAME = "Liked Songs"

    fun create(playlists: List<Playlist>, name: String, id: String): List<Playlist> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return playlists

        return playlists + Playlist(id = id, name = trimmed)
    }

    fun rename(playlists: List<Playlist>, id: String, name: String): List<Playlist> {
        // Reserved: renaming it would not change what the list means, only what it is called.
        if (id == LIKED_ID) return playlists

        val trimmed = name.trim()
        if (trimmed.isEmpty()) return playlists

        return playlists.map { if (it.id == id) it.copy(name = trimmed) else it }
    }

    fun delete(playlists: List<Playlist>, id: String): List<Playlist> {
        // Deleting it would silently discard every like, so it is refused here rather than left to
        // the UI to hide.
        if (id == LIKED_ID) return playlists

        return playlists.filterNot { it.id == id }
    }

    fun likedPaths(playlists: List<Playlist>): Set<String> =
        playlists.firstOrNull { it.id == LIKED_ID }?.trackPaths?.toSet().orEmpty()

    /** Adds or removes one like. The list is created on the first like and kept when emptied. */
    fun toggleLiked(playlists: List<Playlist>, path: String): List<Playlist> {
        val liked = playlists.firstOrNull { it.id == LIKED_ID }
            ?: return playlists + Playlist(id = LIKED_ID, name = LIKED_NAME, trackPaths = listOf(path))

        return if (path in liked.trackPaths) {
            removeTrack(playlists, LIKED_ID, path)
        } else {
            addTrack(playlists, LIKED_ID, path)
        }
    }

    /**
     * Appends a track, ignoring one the playlist already holds. A playlist is a sequence, so the
     * same file listed twice would only mean a row that plays twice in a row.
     */
    fun addTrack(playlists: List<Playlist>, id: String, path: String): List<Playlist> =
        playlists.map { playlist ->
            if (playlist.id != id || path in playlist.trackPaths) {
                playlist
            } else {
                playlist.copy(trackPaths = playlist.trackPaths + path)
            }
        }

    fun removeTrack(playlists: List<Playlist>, id: String, path: String): List<Playlist> =
        playlists.map { playlist ->
            if (playlist.id != id) playlist
            else playlist.copy(trackPaths = playlist.trackPaths.filterNot { it == path })
        }
}
