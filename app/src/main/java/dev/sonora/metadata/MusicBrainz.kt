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
    const val USER_AGENT = "Sonora/0.2.0 (dev.sonora; Android)"

    fun artistSearchUrl(name: String): String =
        "$BASE/artist?query=${encode(name)}&fmt=json&limit=1"

    /**
     * An artist's releases.
     *
     * `type=album` excludes singles and EPs at the source, which is fewer bytes and less to filter.
     */
    fun discographyUrl(artistId: String): String =
        "$BASE/release-group?artist=${encode(artistId)}&type=album&limit=100&fmt=json"

    /**
     * Release groups whose name matches, restricted to albums at the source.
     *
     * The type restriction matters more than it looks. Without it the search returns singles and
     * compilations that outrank the album itself, and "Happier Than Ever" comes back as a single.
     */
    fun releaseSearchUrl(query: String): String =
        "$BASE/release-group?query=${encode("$query AND primarytype:album")}&limit=10&fmt=json"

    /** The best-matching artist for a name, as MusicBrainz ranks them. */
    fun parseArtist(body: String): Artist? = runCatching {
        json.decodeFromString<ArtistPage>(body).artists.firstOrNull()
    }.getOrNull()

    /**
     * The artist whose name is exactly [name], or null.
     *
     * The score cannot stand in for this check. MusicBrainz scores a near miss as highly as an exact
     * hit — an artist lookup for "Happier Than Ever" answers "More Than Ever" at full marks — so
     * comparing the names is the only thing that keeps one artist's albums out of another's search.
     */
    fun parseNamedArtist(body: String, name: String): Artist? {
        val wanted = words(name)
        if (wanted.isEmpty()) return null

        return runCatching {
            json.decodeFromString<ArtistPage>(body).artists
                .firstOrNull { words(it.name) == wanted }
        }.getOrNull()
    }

    /**
     * The studio albums whose title answers the query, best match first.
     *
     * MusicBrainz's score cannot do this filtering. It scores each result against its own title
     * terms rather than against the query, so a search for "zzqxx nonsense" returns an album called
     * "Nonsense" at full marks, and "Happier Than Ever" returns "Happier Than Everybody Else" as a
     * perfectly good studio album. What separates the album the user meant from a coincidental
     * title is whether the words they typed are in that title.
     */
    fun parseReleases(body: String, query: String): List<ReleaseGroup> {
        val wanted = words(query)
        if (wanted.isEmpty()) return emptyList()

        return runCatching {
            json.decodeFromString<ReleaseGroupPage>(body).groups.filter {
                it.isStudioAlbum && words(it.title).containsAll(wanted)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * The words in a title, with case and punctuation set aside.
     *
     * Words rather than a plain substring, because "Happier Than Ever" is a prefix of "Happier Than
     * Everybody Else" once punctuation is stripped — a substring test cannot tell those apart.
     */
    private fun words(text: String): List<String> =
        text.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }

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
    /** MusicBrainz's identifier for this release, and what the cover art is looked up by. */
    val id: String = "",
    val title: String,
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("secondary-types") val secondaryTypes: List<String> = emptyList(),
    @SerialName("artist-credit") val artistCredit: List<ArtistCredit> = emptyList(),
) {
    /** True for a plain album: no live, compilation, remix or soundtrack tagging. */
    val isStudioAlbum: Boolean
        get() = primaryType.equals("Album", ignoreCase = true) && secondaryTypes.isEmpty()

    /** Just the year, since a full date is more precision than a card shows. */
    val year: String? get() = firstReleaseDate?.take(4)?.takeIf { it.length == 4 }

    /**
     * Who it is credited to, or empty.
     *
     * Only a search says this — a discography is asked for by artist, so it already knows. The
     * search has to say it, because the same album title belongs to several different artists and
     * the title alone does not say which one the user is looking at.
     */
    val artistName: String get() = artistCredit.joinToString(" & ") { it.name }
}

/** One credited artist on a release, as a search response spells it. */
@Serializable
data class ArtistCredit(val name: String = "")

@Serializable
private data class ArtistPage(val artists: List<Artist> = emptyList())

@Serializable
data class Artist(val id: String, val name: String = "")

@Serializable
private data class ReleaseGroupPage(
    @SerialName("release-group-count") val count: Int = 0,
    @SerialName("release-groups") val groups: List<ReleaseGroup> = emptyList(),
)
