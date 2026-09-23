package dev.sonora.metadata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusicBrainzClientTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** The real discography response for Billie Eilish, trimmed to keep the file small. */
    private val discography: String = checkNotNull(
        javaClass.getResourceAsStream("/musicbrainz/release-groups.json"),
    ).bufferedReader().readText()

    private val artistSearch = """{"artists":[{"id":"f4abc0b5","name":"Billie Eilish"}]}"""

    @Test
    fun `reads studio albums from a real discography response`() {
        val client = clientFor { url ->
            if (url.contains("/artist?")) artistSearch else discography
        }

        val albums = client.studioAlbums("Billie Eilish")

        assertEquals(
            listOf("HIT ME HARD AND SOFT", "Happier Than Ever", "WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?"),
            albums?.map { it.title },
        )
    }

    @Test
    fun `a release with a secondary type is not an album you are missing`() {
        val client = clientFor { url ->
            if (url.contains("/artist?")) artistSearch else discography
        }

        val titles = client.studioAlbums("Billie Eilish")?.map { it.title }.orEmpty()

        // Live, compilation and remix sets are extras, not the discography proper.
        assertTrue("Live at Third Man Records" !in titles)
        assertTrue("eilish" !in titles)
    }

    @Test
    fun `a year is taken from the release date`() {
        val client = clientFor { url ->
            if (url.contains("/artist?")) artistSearch else discography
        }

        val albums = client.studioAlbums("Billie Eilish").orEmpty()

        assertEquals(listOf("2024", "2021", "2019"), albums.map { it.year })
    }

    @Test
    fun `a second lookup is served from the cache without fetching`() {
        var fetches = 0
        val client = clientFor { url ->
            fetches++
            if (url.contains("/artist?")) artistSearch else discography
        }

        client.studioAlbums("Billie Eilish")
        val afterFirst = fetches
        client.studioAlbums("Billie Eilish")

        assertEquals("two requests the first time", 2, afterFirst)
        assertEquals("and none the second", afterFirst, fetches)
    }

    @Test
    fun `an entry past its life is fetched again`() {
        var now = 1_000L
        var fetches = 0
        val store = MetadataStore(folder.newFolder("cache"), now = { now })
        val client = MusicBrainzClient(store) { url ->
            fetches++
            if (url.contains("/artist?")) artistSearch else discography
        }

        client.studioAlbums("Billie Eilish")

        // Past the discography's life, but not the identifier's.
        now += 31L * 24 * 60 * 60 * 1000
        client.studioAlbums("Billie Eilish")

        assertEquals("only the discography is re-read", 3, fetches)
    }

    @Test
    fun `an unreachable MusicBrainz reports nothing rather than throwing`() {
        val client = clientFor { null }

        assertNull(client.studioAlbums("Billie Eilish"))
    }

    @Test
    fun `a failed fetch is not remembered as an empty answer`() {
        var attempt = 0
        val client = clientFor { url ->
            attempt++
            if (attempt == 1) null else if (url.contains("/artist?")) artistSearch else discography
        }

        assertNull("first attempt fails", client.studioAlbums("Billie Eilish"))
        assertEquals(
            "the retry still finds the albums",
            3,
            client.studioAlbums("Billie Eilish")?.size,
        )
    }

    private fun clientFor(fetch: (String) -> String?): MusicBrainzClient =
        MusicBrainzClient(MetadataStore(folder.newFolder()), fetch)
}

class MusicBrainzSearchTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** The real search response for "Happier Than Ever", trimmed to keep the file small. */
    private val releaseSearch: String = checkNotNull(
        javaClass.getResourceAsStream("/musicbrainz/release-search.json"),
    ).bufferedReader().readText()

    @Test
    fun `a search finds the album and not the look-alikes`() {
        // The response also holds a compilation, a remix set, "Happier Than Everybody Else" and
        // "Greater Than Ever" — all of them studio albums, none of them what was asked for.
        val albums = MusicBrainz.parseReleases(releaseSearch, "Happier Than Ever")

        assertEquals(listOf("Happier Than Ever"), albums.map { it.title })
    }

    @Test
    fun `a search result says who it is by`() {
        val album = MusicBrainz.parseReleases(releaseSearch, "Happier Than Ever").single()

        assertEquals("Billie Eilish", album.artistName)
        assertEquals("2021", album.year)
    }

    @Test
    fun `a half-typed title still finds the album, best match first`() {
        // Deliberately looser: both words are in "Happier Than Everybody Else" too, and a half
        // typed title is not grounds for hiding a title that does contain what was typed.
        val albums = MusicBrainz.parseReleases(releaseSearch, "happier than")

        assertEquals("Happier Than Ever", albums.first().title)
        assertEquals("Billie Eilish", albums.first().artistName)
    }

    @Test
    fun `a query whose words are not in a title matches nothing`() {
        // MusicBrainz scores this 100 against an album called "Nonsense", so the score cannot be
        // what decides it.
        val albums = MusicBrainz.parseReleases(releaseSearch, "zzqxx nonsense")

        assertEquals(emptyList<String>(), albums.map { it.title })
    }

    @Test
    fun `a query with no words in it matches nothing`() {
        val albums = MusicBrainz.parseReleases(releaseSearch, "!!")

        assertEquals(emptyList<String>(), albums.map { it.title })
    }

    @Test
    fun `the search asks for albums only`() {
        val url = MusicBrainz.releaseSearchUrl("Happier Than Ever")

        assertTrue(url, url.startsWith("https://musicbrainz.org/ws/2/release-group?"))
        assertTrue(url, url.contains("query=Happier+Than+Ever+AND+primarytype%3Aalbum"))
        assertTrue(url, url.endsWith("&fmt=json"))
    }

    @Test
    fun `a search is fetched once and then served from the cache`() {
        var fetches = 0
        val client = MusicBrainzClient(MetadataStore(folder.newFolder())) { fetches++; releaseSearch }

        val first = client.searchAlbums("Happier Than Ever")
        client.searchAlbums("Happier Than Ever")

        assertEquals(listOf("Happier Than Ever"), first?.map { it.title })
        assertEquals(1, fetches)
    }

    @Test
    fun `an unreachable MusicBrainz reports nothing rather than throwing`() {
        val client = MusicBrainzClient(MetadataStore(folder.newFolder())) { null }

        assertNull(client.searchAlbums("Happier Than Ever"))
    }
}

class MetadataStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a stored body comes back`() {
        val store = MetadataStore(folder.newFolder())

        store.write("https://example.test/a", "body")

        assertEquals("body", store.read("https://example.test/a", ttlMillis = 1_000))
    }

    @Test
    fun `an entry older than its life is not returned`() {
        var now = 10_000L
        val store = MetadataStore(folder.newFolder(), now = { now })
        store.write("https://example.test/a", "body")

        now += 5_000

        assertNull(store.read("https://example.test/a", ttlMillis = 1_000))
    }

    @Test
    fun `different keys do not collide`() {
        val store = MetadataStore(folder.newFolder())

        store.write("https://example.test/a", "first")
        store.write("https://example.test/b", "second")

        assertEquals("first", store.read("https://example.test/a", ttlMillis = 1_000))
        assertEquals("second", store.read("https://example.test/b", ttlMillis = 1_000))
    }

    @Test
    fun `a missing entry reads as nothing`() {
        val store = MetadataStore(folder.newFolder())

        assertNull(store.read("https://example.test/never", ttlMillis = 1_000))
    }

    @Test
    fun `a url is not used as a filename`() {
        val directory = folder.newFolder()
        val store = MetadataStore(directory)

        store.write("https://example.test/a?query=x&fmt=json", "body")

        val name = directory.listFiles()?.single()?.name.orEmpty()
        assertTrue("stored under a hash, not the url: $name", name.endsWith(".json"))
        assertTrue("no url characters survive", !name.contains(":") && !name.contains("?"))
    }
}
