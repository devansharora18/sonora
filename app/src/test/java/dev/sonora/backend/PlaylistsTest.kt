package dev.sonora.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistsTest {

    private val first = Playlist(id = "a", name = "First", trackPaths = listOf("/one.mp3"))
    private val second = Playlist(id = "b", name = "Second")

    @Test
    fun `create appends a playlist with a trimmed name`() {
        val result = Playlists.create(listOf(first), name = "  Road trip  ", id = "c")

        assertEquals(
            listOf(first, Playlist(id = "c", name = "Road trip")),
            result,
        )
    }

    @Test
    fun `create refuses a blank name`() {
        val playlists = listOf(first)

        assertEquals(playlists, Playlists.create(playlists, name = "   ", id = "c"))
    }

    @Test
    fun `rename changes only the named playlist`() {
        val result = Playlists.rename(listOf(first, second), id = "a", name = "Renamed")

        assertEquals("Renamed", result.first { it.id == "a" }.name)
        assertEquals(second, result.first { it.id == "b" })
    }

    @Test
    fun `rename refuses a blank name`() {
        val playlists = listOf(first)

        assertEquals(playlists, Playlists.rename(playlists, id = "a", name = " "))
    }

    @Test
    fun `delete drops the playlist and leaves the rest`() {
        assertEquals(listOf(second), Playlists.delete(listOf(first, second), id = "a"))
    }

    @Test
    fun `editing an unknown playlist leaves the list unchanged`() {
        val playlists = listOf(first, second)

        assertEquals(playlists, Playlists.rename(playlists, id = "missing", name = "Nope"))
        assertEquals(playlists, Playlists.delete(playlists, id = "missing"))
        assertEquals(playlists, Playlists.addTrack(playlists, id = "missing", path = "/x.mp3"))
        assertEquals(playlists, Playlists.removeTrack(playlists, id = "missing", path = "/one.mp3"))
    }

    @Test
    fun `addTrack appends in order`() {
        val result = Playlists.addTrack(listOf(first), id = "a", path = "/two.mp3")

        assertEquals(listOf("/one.mp3", "/two.mp3"), result.single().trackPaths)
    }

    @Test
    fun `addTrack ignores a track the playlist already holds`() {
        val playlists = listOf(first)

        assertEquals(playlists, Playlists.addTrack(playlists, id = "a", path = "/one.mp3"))
    }

    @Test
    fun `removeTrack drops the path and keeps the rest`() {
        val playlist = Playlist(id = "a", name = "First", trackPaths = listOf("/one.mp3", "/two.mp3"))

        val result = Playlists.removeTrack(listOf(playlist), id = "a", path = "/one.mp3")

        assertEquals(listOf("/two.mp3"), result.single().trackPaths)
    }

    @Test
    fun `removeTrack leaves a playlist that does not hold the path alone`() {
        val playlists = listOf(first)

        assertEquals(playlists, Playlists.removeTrack(playlists, id = "a", path = "/missing.mp3"))
    }

    @Test
    fun `first like creates the liked list`() {
        val result = Playlists.toggleLiked(listOf(first), path = "/one.mp3")

        val liked = result.single { it.id == Playlists.LIKED_ID }
        assertEquals(Playlists.LIKED_NAME, liked.name)
        assertEquals(listOf("/one.mp3"), liked.trackPaths)
    }

    @Test
    fun `liking again removes the like and leaves the list standing`() {
        val liked = Playlist(Playlists.LIKED_ID, Playlists.LIKED_NAME, listOf("/one.mp3"))

        val result = Playlists.toggleLiked(listOf(liked), path = "/one.mp3")

        assertEquals(emptyList<String>(), result.single { it.id == Playlists.LIKED_ID }.trackPaths)
    }

    @Test
    fun `toggling a like leaves the other playlists untouched`() {
        val result = Playlists.toggleLiked(listOf(first, second), path = "/one.mp3")

        assertEquals(first, result.single { it.id == "a" })
        assertEquals(second, result.single { it.id == "b" })
    }

    @Test
    fun `likedPaths is empty before anything is liked`() {
        assertEquals(emptySet<String>(), Playlists.likedPaths(listOf(first, second)))
    }

    @Test
    fun `likedPaths reports what has been liked`() {
        val liked = Playlist(Playlists.LIKED_ID, Playlists.LIKED_NAME, listOf("/one.mp3"))

        assertEquals(setOf("/one.mp3"), Playlists.likedPaths(listOf(first, liked)))
    }

    @Test
    fun `liked songs cannot be renamed`() {
        val liked = Playlist(Playlists.LIKED_ID, Playlists.LIKED_NAME, listOf("/one.mp3"))
        val playlists = listOf(liked)

        assertEquals(playlists, Playlists.rename(playlists, id = Playlists.LIKED_ID, name = "Mine"))
    }

    @Test
    fun `liked songs cannot be deleted`() {
        val liked = Playlist(Playlists.LIKED_ID, Playlists.LIKED_NAME, listOf("/one.mp3"))
        val playlists = listOf(liked)

        assertEquals(playlists, Playlists.delete(playlists, id = Playlists.LIKED_ID))
    }
}
