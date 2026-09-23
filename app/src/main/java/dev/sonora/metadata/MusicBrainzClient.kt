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
    fun studioAlbums(artist: String): List<ReleaseGroup>? {
        val artistId = cached(MusicBrainz.artistSearchUrl(artist), TTL_ARTIST_ID)?.let {
            MusicBrainz.parseArtistId(it)
        } ?: return null

        val discography = cached(MusicBrainz.discographyUrl(artistId), TTL_DISCOGRAPHY)
            ?: return null

        return MusicBrainz.parseStudioAlbums(discography)
    }

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
    }
}
