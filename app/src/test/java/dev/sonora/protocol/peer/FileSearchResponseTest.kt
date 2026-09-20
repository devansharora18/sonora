package dev.sonora.protocol.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire bytes come from [SearchWire], which builds them independently of the parser here — so
 * a mistake in the parser cannot be mirrored by the test that checks it.
 */
class FileSearchResponseTest {

    @Test
    fun `parses a response with attributes`() {
        val response = parse(
            SearchWire.searchResponseBody(
                username = "peer_user",
                token = 1234,
                files = listOf(
                    SearchWire.fileEntry(
                        "Music\\Artist\\track.flac",
                        size = 40_000_000,
                        attributes = mapOf(
                            FileAttributes.DURATION to 240,
                            FileAttributes.SAMPLE_RATE to 44100,
                            FileAttributes.BIT_DEPTH to 16,
                        ),
                    ),
                ),
                freeSlot = true,
                speed = 500_000,
                queue = 3,
            ),
        )

        assertEquals("peer_user", response.username)
        assertEquals(1234L, response.token)
        assertTrue(response.hasFreeUploadSlot)
        assertEquals(500_000L, response.averageSpeed)
        assertEquals(3L, response.queueLength)

        val hit = response.files.single()
        assertEquals("Music\\Artist\\track.flac", hit.filename)
        assertEquals(40_000_000L, hit.size)
        assertEquals(240L, hit.attributes.durationSeconds)
        assertEquals(44100L, hit.attributes.sampleRateHz)
        assertEquals(16L, hit.attributes.bitDepth)
    }

    @Test
    fun `parses multiple files and the lossy attribute set`() {
        val response = parse(
            SearchWire.searchResponseBody(
                username = "peer_user",
                token = 99,
                files = listOf(
                    SearchWire.fileEntry(
                        "a.mp3",
                        5_000_000,
                        mapOf(FileAttributes.BITRATE to 320, FileAttributes.VBR to 0),
                    ),
                    SearchWire.fileEntry(
                        "b.mp3",
                        6_000_000,
                        mapOf(FileAttributes.BITRATE to 192, FileAttributes.VBR to 1),
                    ),
                ),
                freeSlot = false,
            ),
        )

        assertEquals(2, response.files.size)
        assertEquals(320L, response.files[0].attributes.bitrateKbps)
        assertEquals(false, response.files[0].attributes.vbr)
        assertEquals(true, response.files[1].attributes.vbr)
        assertFalse(response.hasFreeUploadSlot)
    }

    @Test
    fun `tolerates a message with no trailing fields`() {
        val response = parse(
            SearchWire.searchResponseBody(
                username = "peer_user",
                token = 7,
                files = listOf(SearchWire.fileEntry("a.flac", 1, emptyMap())),
                trailingUnknown = false,
            ),
        )

        assertEquals(1, response.files.size)
        assertEquals(emptyList<SharedFile>(), response.privateFiles)
    }

    @Test
    fun `works around the oversized-file bug in Soulseek NS`() {
        // A >2 GiB file where the top four bytes arrived as 0xFFFFFFFF instead of zeros.
        val realSize = 3_000_000_000L
        val corrupted = (0xFFFF_FFFFL shl 32) or realSize

        val response = parse(
            SearchWire.searchResponseBody(
                username = "peer_user",
                token = 1,
                files = listOf(SearchWire.fileEntry("huge.flac", corrupted, emptyMap())),
            ),
        )

        assertEquals(realSize, response.files.single().size)
    }

    @Test
    fun `body is actually compressed`() {
        val plain = SearchWire.searchResponseBody(
            username = "peer_user",
            token = 1,
            files = listOf(SearchWire.fileEntry("a.flac", 1, emptyMap())),
        )

        assertTrue(
            "expected zlib compression to change the bytes",
            plain.size != SearchWire.zlib(plain).size,
        )

        val uncompressed = runCatching { FileSearchResponse.parse(plain) }
        assertTrue("an uncompressed body should fail to inflate", uncompressed.isFailure)
    }

    private fun parse(body: ByteArray): SearchResponse =
        FileSearchResponse.parse(SearchWire.zlib(body))
}
