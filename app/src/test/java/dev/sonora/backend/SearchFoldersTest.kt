package dev.sonora.backend

import dev.sonora.protocol.peer.FileAttributes
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchFoldersTest {

    private fun hit(peer: String, filename: String, size: Long = 1_000L) = SearchHit(
        peer = peer,
        filename = filename,
        size = size,
        attributes = FileAttributes(),
        averageSpeed = 0,
        hasFreeUploadSlot = true,
        queueLength = 0,
    )

    @Test
    fun `results in the same peer folder are grouped together`() {
        val first = hit("peer_a", "@@peer_a\\Music\\Kid A\\01.flac")
        val second = hit("peer_a", "@@peer_a\\Music\\Kid A\\02.flac")
        val elsewhere = hit("peer_a", "@@peer_a\\Music\\Amnesiac\\01.flac")

        val folder = SearchFolders.folderOf(listOf(first, second, elsewhere), first)

        assertEquals(listOf(first, second), folder)
    }

    @Test
    fun `the same folder name on another peer is a different album`() {
        val mine = hit("peer_a", "@@peer_a\\Music\\Greatest Hits\\01.flac")
        val theirs = hit("peer_b", "@@peer_b\\Music\\Greatest Hits\\01.flac")

        assertEquals(listOf(mine), SearchFolders.folderOf(listOf(mine, theirs), mine))
    }

    @Test
    fun `a result at the peer root has an empty folder`() {
        assertEquals("", SearchFolders.folderOf(hit("peer_a", "loose.flac")))
    }
}
