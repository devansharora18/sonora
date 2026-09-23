package dev.sonora.backend

/**
 * The Soulseek query that finds more of something you already have.
 *
 * Soulseek has no catalogue to browse — it can only be searched — so "find more like this" has to
 * be expressed as a query. These rules decide what that query is, and they are the whole of the
 * handoff from "something I have" to "something I could get".
 */
object SearchQueries {

    /**
     * Names we invented for grouping, which must never reach a query.
     *
     * They are labels, not artist names: searching for "Various artists" would match nothing,
     * and searching for "Unknown artist" would match the wrong things.
     */
    private val PLACEHOLDERS = setOf(
        LibraryGrouping.VARIOUS_ARTISTS,
        LibraryGrouping.UNKNOWN_ARTIST,
    )

    /** More by an artist: their name, and nothing else. */
    fun forArtist(artist: String): String = artist.trim()

    /**
     * The album, prefixed with its artist.
     *
     * Peers lay music out as `Artist\Album\…` and filenames usually carry the artist too, so
     * "artist album" matches far more than the album name on its own — which is exactly what an
     * album title as generic as "Greatest Hits" needs.
     */
    fun forAlbum(album: String, artist: String): String {
        val name = album.trim()
        val who = artist.trim()

        return if (who.isEmpty() || PLACEHOLDERS.any { it.equals(who, ignoreCase = true) }) {
            name
        } else {
            "$who $name"
        }
    }
}
