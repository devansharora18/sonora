package dev.sonora.metadata

/**
 * Reads MusicBrainz, caching every answer and fetching only what is not already known.
 *
 * The fetch is injected so this can be tested without a network, and so the rate limiting and
 * transport stay in one place rather than being spread through the parsing.
 *
 * Treated as best-effort throughout: this enriches what the user already has, so a failure means
 * a row is absent, never that something is broken.
 */
class MusicBrainzClient(
    private val store: MetadataStore,
    private val fetch: (url: String) -> String?,
) {

    /**
     * Studio albums by [artist], or null when MusicBrainz could not be reached.
     *
     * Two requests at most, and none at all once both are cached: an artist's identifier does not
     * change, and their discography changes when they release something.
     */
    fun studioAlbums(artist: String): List<ReleaseGroup>? =
        artistFor(artist, exactName = false)?.let(::discographyOf)

    /**
     * Studio albums matching [query], or null when MusicBrainz could not be reached.
     *
     * One title search, cached, plus an artist lookup when the query is somebody's name.
     *
     * An artist's albums come first because the query named them: an album *titled* with an artist's
     * name is usually a tribute to that artist rather than their own record, so the title matches
     * are the weaker answer and belong underneath.
     */
    fun searchAlbums(query: String): List<ReleaseGroup>? {
        val byTitle = cached(MusicBrainz.releaseSearchUrl(query), TTL_SEARCH)?.let {
            MusicBrainz.parseReleases(it, query)
        } ?: return null

        val byArtist = artistFor(query, exactName = true)?.let(::discographyOf).orEmpty()

        return (byArtist + byTitle).distinctBy { it.id }
    }

    /**
     * An artist for a name.
     *
     * Two callers want different things from the same lookup. A library artist name is best-effort:
     * the tag may read "Billie Eilish feat. Khalid", and refusing to look that up would lose the
     * discography. A search box is not: there the name has to match, or the albums belong to
     * somebody else.
     */
    private fun artistFor(name: String, exactName: Boolean): Artist? {
        val body = cached(MusicBrainz.artistSearchUrl(name), TTL_ARTIST_ID) ?: return null

        return if (exactName) {
            MusicBrainz.parseNamedArtist(body, name)
        } else {
            MusicBrainz.parseArtist(body)
        }
    }

    /**
     * An artist's albums.
     *
     * The response does not repeat who they are by — a discography is asked for by artist, so the
     * credit is implied. Filling it in is what makes these albums say whose they are, the way a
     * search result does; without it, tapping one could only search for the album title alone.
     */
    private fun discographyOf(artist: Artist): List<ReleaseGroup>? =
        cached(MusicBrainz.discographyUrl(artist.id), TTL_DISCOGRAPHY)
            ?.let(MusicBrainz::parseStudioAlbums)
            ?.map { it.copy(artistCredit = listOf(ArtistCredit(artist.name))) }

    /**
     * Cache first, then network.
     *
     * A failed fetch is deliberately not cached, so a temporary outage is retried rather than
     * remembered as "this artist has nothing".
     */
    private fun cached(url: String, ttlMillis: Long): String? {
        store.read(url, ttlMillis)?.let { return it }

        val body = fetch(url) ?: return null
        store.write(url, body)

        return body
    }

    companion object {
        /** An identifier for a name is effectively permanent. */
        private const val TTL_ARTIST_ID = 90L * 24 * 60 * 60 * 1000

        /** Long, but short enough that a new album shows up without waiting forever. */
        private const val TTL_DISCOGRAPHY = 30L * 24 * 60 * 60 * 1000

        /** A search is a question about a name, and the answer does not change. */
        private const val TTL_SEARCH = 30L * 24 * 60 * 60 * 1000
    }
}
