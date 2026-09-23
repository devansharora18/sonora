package dev.sonora.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayHistoryTest {

    private val first = PlayedTrack("/music/first.flac", playedAt = 1_000L)
    private val second = PlayedTrack("/music/second.flac", playedAt = 2_000L)

    @Test
    fun `the newest track goes first`() {
        val history = PlayHistory.record(listOf(second), "/music/third.flac", at = 3_000L)

        assertEquals(
            listOf("/music/third.flac", "/music/second.flac"),
            history.map { it.path },
        )
    }

    @Test
    fun `the time it started is kept`() {
        val history = PlayHistory.record(emptyList(), "/music/first.flac", at = 1_234L)

        assertEquals(1_234L, history.single().playedAt)
    }

    @Test
    fun `playing a track again moves it to the front rather than duplicating it`() {
        val history = PlayHistory.record(listOf(second, first), first.path, at = 3_000L)

        assertEquals(listOf(first.path, second.path), history.map { it.path })
    }

    @Test
    fun `a track with no path is not recorded`() {
        val history = listOf(first)

        assertEquals(history, PlayHistory.record(history, "  ", at = 3_000L))
    }

    @Test
    fun `the list is capped, dropping the oldest`() {
        val history = (1..PlayHistory.MAX).map { PlayedTrack("/music/$it.flac", it.toLong()) }

        val recorded = PlayHistory.record(history, "/music/newest.flac", at = 9_000L)

        assertEquals(PlayHistory.MAX, recorded.size)
        assertEquals("/music/newest.flac", recorded.first().path)
        // The oldest entry is the one pushed out.
        assertEquals("/music/${PlayHistory.MAX - 1}.flac", recorded.last().path)
    }
}
