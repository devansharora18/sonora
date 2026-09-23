package dev.sonora.ui

import dev.sonora.backend.SearchHit
import dev.sonora.backend.SearchState
import dev.sonora.protocol.peer.FileAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The line under the search controls.
 *
 * Worth testing because it is the one place the screen can contradict itself: the results are two
 * independent sources, and a line about one of them sitting above the other reads as wrong.
 */
class SearchStatusTest {

    @Test
    fun `nothing is said before anything has been searched for`() {
        assertNull(statusNote(SearchState(), showSoulseek = true, catalogueShown = false))
    }

    @Test
    fun `matches are counted while the search is still running`() {
        val state = SearchState(query = "kid a", searching = true, matched = 12)

        assertEquals("Searching\u2026 12 match(es) so far", statusNote(state, true, false))
    }

    @Test
    fun `no results is not said over a row of catalogue matches`() {
        val state = SearchState(query = "kid a")

        assertNull(statusNote(state, showSoulseek = true, catalogueShown = true))
    }

    @Test
    fun `no results is said when nothing came back from anywhere`() {
        val state = SearchState(query = "kid a")

        assertEquals("No results for \u201ckid a\u201d.", statusNote(state, true, false))
    }

    @Test
    fun `the peer count is not reported while the peer results are hidden`() {
        val state = SearchState(query = "kid a", hits = listOf(hit()), matched = 3, peers = 2)

        assertNull(statusNote(state, showSoulseek = false, catalogueShown = true))
        assertEquals("3 match(es) from 2 peer(s)", statusNote(state, true, true))
    }

    @Test
    fun `nothing is said while the peer results are hidden and nothing is in the catalogue`() {
        val state = SearchState(query = "kid a", hits = listOf(hit()), matched = 3, peers = 2)

        assertNull(statusNote(state, showSoulseek = false, catalogueShown = false))
    }

    private fun hit() = SearchHit(
        peer = "peer_a",
        filename = "@@peer_a\\Music\\Kid A\\01.flac",
        size = 1_000L,
        attributes = FileAttributes(),
        averageSpeed = 0,
        hasFreeUploadSlot = true,
        queueLength = 0,
    )
}
