package dev.sonora.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryTest {

    private fun album(title: String) = ReleaseGroup(title = title, primaryType = "Album")

    @Test
    fun `an album already in the library is not suggested`() {
        val releases = listOf(album("Happier Than Ever"), album("HIT ME HARD AND SOFT"))

        val missing = Discovery.missingAlbums(listOf("Happier Than Ever"), releases)

        assertEquals(listOf("HIT ME HARD AND SOFT"), missing.map { it.title })
    }

    @Test
    fun `punctuation and case do not make two albums different`() {
        val releases = listOf(album("Don't Smile at Me"))

        // The library's tag has no apostrophe and different capitalisation.
        val missing = Discovery.missingAlbums(listOf("dont smile at me"), releases)

        assertEquals(emptyList<String>(), missing.map { it.title })
    }

    @Test
    fun `an artist with nothing in the library is suggested everything`() {
        val releases = listOf(album("First"), album("Second"))

        assertEquals(2, Discovery.missingAlbums(emptyList(), releases).size)
    }

    @Test
    fun `the discography order is kept`() {
        val releases = listOf(album("Oldest"), album("Middle"), album("Newest"))

        val missing = Discovery.missingAlbums(listOf("Middle"), releases)

        assertEquals(listOf("Oldest", "Newest"), missing.map { it.title })
    }

    @Test
    fun `a title that differs only by spacing matches`() {
        val releases = listOf(album("WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?"))

        val missing = Discovery.missingAlbums(
            listOf("When We All Fall Asleep Where Do We Go"),
            releases,
        )

        assertEquals(emptyList<String>(), missing.map { it.title })
    }
}
