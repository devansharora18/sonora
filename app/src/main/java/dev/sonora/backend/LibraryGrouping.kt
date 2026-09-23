package dev.sonora.backend

/**
 * Albums and artists, derived from the flat track list.
 *
 * Pure, so the awkward cases — a track with no album tag, an album whose tracks have different
 * artists — are decided once here and can be tested without a device.
 */
object LibraryGrouping {

    const val UNKNOWN_ALBUM = "Unknown album"
    const val UNKNOWN_ARTIST = "Unknown artist"

    /** Reported for an album whose tracks disagree on artist, which is what a compilation looks like. */
    const val VARIOUS_ARTISTS = "Various artists"

    data class Album(val name: String, val artist: String, val tracks: List<LibraryTrack>)

    data class Artist(val name: String, val tracks: List<LibraryTrack>)

    /**
     * Grouped by album name alone, not by album *and* artist: a compilation has a different artist
     * on every track, so including the artist in the key would shatter it into one album per track.
     */
    fun albums(tracks: List<LibraryTrack>): List<Album> =
        tracks.groupBy { it.album.orUnknown(UNKNOWN_ALBUM) }
            .map { (name, group) ->
                Album(
                    name = name,
                    artist = sharedArtist(group),
                    // Track numbers are not in the tags being read, so filename order is the closest
                    // available approximation of the album's own order.
                    tracks = group.sortedBy { it.file.name.lowercase() },
                )
            }
            .sortedBy { it.name.lowercase() }

    fun artists(tracks: List<LibraryTrack>): List<Artist> =
        tracks.groupBy { it.artist.orUnknown(UNKNOWN_ARTIST) }
            .map { (name, group) -> Artist(name, group.sortedBy { it.title.lowercase() }) }
            .sortedBy { it.name.lowercase() }

    /**
     * Albums ordered by when their newest track arrived.
     *
     * The library sorts by title, which is right for browsing and useless for "what did I just
     * get". This is the ordering Home needs, and it is why it is derived from the files rather
     * than stored: a download's arrival time is already on disk.
     */
    fun recentAlbums(tracks: List<LibraryTrack>, limit: Int): List<Album> =
        albums(tracks)
            .sortedByDescending { album -> album.tracks.maxOf { it.file.lastModified() } }
            .take(limit)

    private fun sharedArtist(tracks: List<LibraryTrack>): String {
        val artists = tracks.map { it.artist.orUnknown(UNKNOWN_ARTIST) }.distinct()
        return if (artists.size == 1) artists.single() else VARIOUS_ARTISTS
    }

    private fun String?.orUnknown(fallback: String): String =
        this?.trim().orEmpty().ifEmpty { fallback }
}
