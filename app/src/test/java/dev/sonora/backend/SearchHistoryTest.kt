package dev.sonora.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryTest {

    @Test
    fun `the newest query goes first`() {
        val history = SearchHistory.record(listOf("older"), "newer")

        assertEquals(listOf("newer", "older"), history)
    }

    @Test
    fun `queries are trimmed`() {
        assertEquals(listOf("ocean eyes"), SearchHistory.record(emptyList(), "  ocean eyes  "))
    }

    @Test
    fun `a blank query is not recorded`() {
        val history = listOf("ocean eyes")

        assertEquals(history, SearchHistory.record(history, "   "))
    }

    @Test
    fun `repeating a query moves it to the front rather than duplicating it`() {
        val history = SearchHistory.record(listOf("second", "first"), "first")

        assertEquals(listOf("first", "second"), history)
    }

    @Test
    fun `repeating a query in a different case still matches`() {
        val history = SearchHistory.record(listOf("Ocean Eyes"), "ocean eyes")

        assertEquals(listOf("ocean eyes"), history)
    }

    @Test
    fun `the list is capped, dropping the oldest`() {
        val history = (1..SearchHistory.MAX).map { "query $it" }

        val recorded = SearchHistory.record(history, "newest")

        assertEquals(SearchHistory.MAX, recorded.size)
        assertEquals("newest", recorded.first())
        // The oldest entry, "query 1", is the one pushed out.
        assertEquals("query ${SearchHistory.MAX - 1}", recorded.last())
    }
}
