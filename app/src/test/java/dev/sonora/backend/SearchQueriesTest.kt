package dev.sonora.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchQueriesTest {

    @Test
    fun `an artist query is just the name`() {
        assertEquals("Billie Eilish", SearchQueries.forArtist("  Billie Eilish  "))
    }

    @Test
    fun `an album query leads with the artist`() {
        assertEquals(
            "Billie Eilish dont smile at me",
            SearchQueries.forAlbum(album = "dont smile at me", artist = "Billie Eilish"),
        )
    }

    @Test
    fun `a compilation is searched by album alone`() {
        // "Various artists" is a grouping label we invented. In a query it would match nothing.
        assertEquals(
            "Now That's What I Call Music 64",
            SearchQueries.forAlbum(
                album = "Now That's What I Call Music 64",
                artist = LibraryGrouping.VARIOUS_ARTISTS,
            ),
        )
    }

    @Test
    fun `an unknown artist is not put in the query`() {
        assertEquals(
            "Some Album",
            SearchQueries.forAlbum(album = "Some Album", artist = LibraryGrouping.UNKNOWN_ARTIST),
        )
    }

    @Test
    fun `an album with no artist is searched by album alone`() {
        assertEquals("Some Album", SearchQueries.forAlbum(album = "Some Album", artist = "   "))
    }
}
