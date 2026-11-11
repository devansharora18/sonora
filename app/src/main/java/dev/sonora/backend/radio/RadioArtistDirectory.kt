package dev.sonora.backend.radio

import dev.sonora.backend.taste.TrackIdentity
import dev.sonora.ytm.YtmBrowse
import dev.sonora.ytm.YtmCatalogSearch
import dev.sonora.ytm.YtmEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a picked artist to the two things a mix needs from YouTube Music.
 *
 * A name alone is enough to pick an artist — the taste model is keyed by name — but a mix needs an
 * artist page to borrow a track from, and a radio needs that track's video id. Both cost a request,
 * so both are cached for the life of the process: a mix refill must not re-resolve five artists
 * every time the queue runs low.
 *
 * Every lookup degrades to null rather than throwing. A seed that cannot resolve is a seed the mix
 * skips and the screen names, not a mix that fails.
 */
class RadioArtistDirectory(
    private val search: suspend (String) -> List<YtmEntity> = YtmCatalogSearch::artists,
    private val page: suspend (String) -> YtmBrowse.Page = { YtmBrowse.page(it) },
) {

    /** Name key -> the resolved artist. Empty string means "asked, no answer". */
    private val resolved = ConcurrentHashMap<String, RadioSeed>()

    /** Artist id -> a video id to seed a radio with. Empty string means "asked, no answer". */
    private val videoIds = ConcurrentHashMap<String, String>()

    /**
     * The artist behind [name], with its id and picture, or null when YouTube has no such artist.
     *
     * The first result wins unless an exact name match is in the list: a search for "Nirvana"
     * returns the band and a tribute act, and the band is the one with the id the listener meant.
     */
    suspend fun resolve(name: String): RadioSeed? {
        val key = TrackIdentity.artistKey(name)
        if (key.isBlank()) return null

        resolved[key]?.let { return it.takeIf { seed -> seed.name.isNotBlank() } }

        val found = runCatching { search(name) }.getOrDefault(emptyList())
        val match = found.firstOrNull { TrackIdentity.artistKey(it.title) == key }
            ?: found.firstOrNull()

        val seed = match?.let {
            RadioSeed(name = it.title, browseId = it.browseId, artworkUrl = it.artworkUrl)
        }

        resolved[key] = seed ?: NO_ANSWER
        return seed
    }

    /**
     * A video id that stands for [seed]'s artist, for seeding a radio.
     *
     * The artist's own first track: the radio endpoint is seeded by a video, not by an artist, and
     * an artist page's own track list is the most representative one there is.
     */
    suspend fun trackFor(seed: RadioSeed): String? {
        val artistId = seed.browseId ?: resolve(seed.name)?.browseId ?: return null

        videoIds[artistId]?.let { return it.takeIf { id -> id.isNotBlank() } }

        val videoId = runCatching { page(artistId) }.getOrNull()
            ?.tracks
            ?.firstOrNull()
            ?.videoId

        videoIds[artistId] = videoId ?: NO_VIDEO
        return videoId
    }

    private companion object {
        /** ConcurrentHashMap refuses null values, so "already asked, nothing there" needs a value. */
        val NO_ANSWER = RadioSeed(name = "")

        const val NO_VIDEO = ""
    }
}
