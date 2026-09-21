package dev.sonora.backend

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaylistStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `playlists round-trip through the file`() {
        val file = File(folder.root, "playlists.json")
        val playlists = listOf(
            Playlist(
                id = "a",
                name = "Late night",
                trackPaths = listOf("/music/one.mp3", "/music/two.flac"),
            ),
            Playlist(id = "b", name = "Empty"),
        )

        PlaylistStore(file).save(playlists)

        assertEquals(playlists, PlaylistStore(file).load())
        assertFalse(File(folder.root, "playlists.json.tmp").exists())
    }

    @Test
    fun `missing file loads as no playlists`() {
        val file = File(folder.root, "not-written-yet.json")

        assertEquals(emptyList<Playlist>(), PlaylistStore(file).load())
    }

    @Test
    fun `unreadable document loads as no playlists rather than throwing`() {
        val file = File(folder.root, "playlists.json")
        file.writeText("{ this is not json")

        assertEquals(emptyList<Playlist>(), PlaylistStore(file).load())
    }

    @Test
    fun `saving replaces the previous document instead of appending to it`() {
        val file = File(folder.root, "playlists.json")

        PlaylistStore(file).save(listOf(Playlist(id = "a", name = "First")))
        PlaylistStore(file).save(listOf(Playlist(id = "b", name = "Second")))

        assertEquals(listOf(Playlist(id = "b", name = "Second")), PlaylistStore(file).load())
    }

    @Test
    fun `saving creates the parent directory`() {
        val file = File(File(folder.root, "nested/dir"), "playlists.json")

        PlaylistStore(file).save(listOf(Playlist(id = "a", name = "First")))

        assertTrue(file.exists())
        assertEquals(1, PlaylistStore(file).load().size)
    }

    @Test
    fun `characters that need escaping survive a round-trip`() {
        val file = File(folder.root, "playlists.json")
        val awkward = Playlist(id = "a", name = "Rock \"n\" roll \\ metal\nsecond line")

        PlaylistStore(file).save(listOf(awkward))

        assertEquals(listOf(awkward), PlaylistStore(file).load())
    }
}
