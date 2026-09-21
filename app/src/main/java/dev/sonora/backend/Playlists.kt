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

    fun create(playlists: List<Playlist>, name: String, id: String): List<Playlist> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return playlists

        return playlists + Playlist(id = id, name = trimmed)
    }

    fun rename(playlists: List<Playlist>, id: String, name: String): List<Playlist> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return playlists

        return playlists.map { if (it.id == id) it.copy(name = trimmed) else it }
    }

    fun delete(playlists: List<Playlist>, id: String): List<Playlist> =
        playlists.filterNot { it.id == id }

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
