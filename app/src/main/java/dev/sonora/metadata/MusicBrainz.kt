package dev.sonora.metadata

import java.net.URLEncoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * MusicBrainz: what exists, as opposed to what the network happens to hold.
 *
 * Only URLs and parsing live here, so both are testable without a network. Fetching, caching and
 * rate limiting are [MusicBrainzClient]'s job.
 *
 * Two requests answer "what else did this artist release": one to turn a name into an identifier,
 * and one to list their releases. Both are stable enough to keep for a long time, which is what
 * makes the client's caching worthwhile.
 */
object MusicBrainz {

    private const val BASE = "https://musicbrainz.org/ws/2"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * MusicBrainz wants a descriptive User-Agent and blocks anonymous ones.
     *
     * Their guidance asks for a contact URL as well; the package name stands in until there is a
     * public repository to point at.
     */
    const val USER_AGENT = "Sonora/0.1.0 (dev.sonora; Android)"

    fun artistSearchUrl(name: String): String =
        "$BASE/artist?query=${encode(name)}&fmt=json&limit=1"

    /**
     * An artist's releases.
     *
     * `type=album` excludes singles and EPs at the source, which is fewer bytes and less to filter.
     */
    fun discographyUrl(artistId: String): String =
        "$BASE/release-group?artist=${encode(artistId)}&type=album&limit=100&fmt=json"

    fun parseArtistId(body: String): String? = runCatching {
        json.decodeFromString<ArtistPage>(body).artists.firstOrNull()?.id
    }.getOrNull()

    /**
     * Studio albums: releases typed as an album with no secondary type.
     *
     * The secondary type is what separates an album from a live record, a compilation or a remix
     * set — and those are not "the albums you are missing", they are extras.
     */
    fun parseStudioAlbums(body: String): List<ReleaseGroup> = runCatching {
        json.decodeFromString<ReleaseGroupPage>(body).groups.filter { it.isStudioAlbum }
    }.getOrDefault(emptyList())

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}

/** A release MusicBrainz knows about. */
@Serializable
data class ReleaseGroup(
    val title: String,
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("secondary-types") val secondaryTypes: List<String> = emptyList(),
) {
    /** True for a plain album: no live, compilation, remix or soundtrack tagging. */
    val isStudioAlbum: Boolean
        get() = primaryType.equals("Album", ignoreCase = true) && secondaryTypes.isEmpty()

    /** Just the year, since a full date is more precision than a card shows. */
    val year: String? get() = firstReleaseDate?.take(4)?.takeIf { it.length == 4 }
}

@Serializable
private data class ArtistPage(val artists: List<Artist> = emptyList())

@Serializable
data class Artist(val id: String, val name: String = "")

@Serializable
private data class ReleaseGroupPage(
    @SerialName("release-group-count") val count: Int = 0,
    @SerialName("release-groups") val groups: List<ReleaseGroup> = emptyList(),
)
