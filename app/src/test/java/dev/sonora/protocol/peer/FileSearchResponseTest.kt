package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageWriter
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests build the wire bytes independently of the production parser (raw
 * [MessageWriter] plus [Deflater] rather than any shared helper), so a mistake in the parser
 * cannot be mirrored by the test that checks it.
 */
class FileSearchResponseTest {

    @Test
    fun `parses a response with attributes`() {
        val response = FileSearchResponse.parse(
            zlib(
                body(
                    username = "peer_user",
                    token = 1234,
                    files = listOf(
                        file(
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
        val response = FileSearchResponse.parse(
            zlib(
                body(
                    username = "peer_user",
                    token = 99,
                    files = listOf(
                        file("a.mp3", 5_000_000, mapOf(FileAttributes.BITRATE to 320, FileAttributes.VBR to 0)),
                        file("b.mp3", 6_000_000, mapOf(FileAttributes.BITRATE to 192, FileAttributes.VBR to 1)),
                    ),
                    freeSlot = false,
                    speed = 1,
                    queue = 0,
                ),
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
        val response = FileSearchResponse.parse(
            zlib(
                body(
                    username = "peer_user",
                    token = 7,
                    files = listOf(file("a.flac", 1, emptyMap())),
                    freeSlot = true,
                    speed = 0,
                    queue = 0,
                    trailingUnknown = false,
                ),
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

        val response = FileSearchResponse.parse(
            zlib(
                body(
                    username = "peer_user",
                    token = 1,
                    files = listOf(file("huge.flac", corrupted, emptyMap())),
                    freeSlot = true,
                    speed = 0,
                    queue = 0,
                ),
            ),
        )

        assertEquals(realSize, response.files.single().size)
    }

    @Test
    fun `body is actually compressed`() {
        val plain = body("peer_user", 1, listOf(file("a.flac", 1, emptyMap())), true, 0, 0)
        val compressed = zlib(plain)

        assertTrue("expected zlib compression to change the bytes", plain.size != compressed.size)

        // A raw (uncompressed) body must not parse.
        runCatching { FileSearchResponse.parse(plain) }.let {
            assertTrue("uncompressed body should fail to inflate", it.isFailure)
        }
    }

    // -- wire-format builders, deliberately independent of production code --

    private fun body(
        username: String,
        token: Long,
        files: List<ByteArray>,
        freeSlot: Boolean,
        speed: Long,
        queue: Long,
        trailingUnknown: Boolean = true,
    ): ByteArray {
        val writer = MessageWriter()
            .writeString(username)
            .writeUInt32(token)
            .writeUInt32(files.size.toLong())

        files.forEach { writer.writeRaw(it) }

        writer.writeBool(freeSlot).writeUInt32(speed).writeUInt32(queue)
        if (trailingUnknown) writer.writeUInt32(0)

        return writer.toByteArray()
    }

    private fun file(name: String, size: Long, attributes: Map<Long, Long>): ByteArray {
        val writer = MessageWriter()
            .writeByte(1)
            .writeString(name)
            .writeUInt64(size)
            .writeUInt32(0) // obsolete extension field, empty
            .writeUInt32(attributes.size.toLong())

        attributes.forEach { (code, value) -> writer.writeUInt32(code).writeUInt32(value) }

        return writer.toByteArray()
    }

    private fun zlib(bytes: ByteArray): ByteArray {
        val deflater = Deflater(4)
        deflater.setInput(bytes)
        deflater.finish()

        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()

        return out.toByteArray()
    }
}
