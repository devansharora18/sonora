package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageReader
import dev.sonora.protocol.MessageWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTransferTest {

    @Test
    fun `queue upload carries only the file path`() {
        val body = QueueUpload.request("Music\\Artist\\track.flac")

        val reader = MessageReader(body)
        assertEquals("Music\\Artist\\track.flac", reader.readString())
        assertEquals("no token should follow the path", 0, reader.remaining)
    }

    @Test
    fun `parses an offered upload with its size`() {
        val body = MessageWriter()
            .writeUInt32(TransferRequest.DIRECTION_UPLOAD)
            .writeUInt32(77)
            .writeString("Music\\Artist\\track.flac")
            .writeUInt64(1_234_567)
            .toByteArray()

        val request = TransferRequest.parse(body)

        assertEquals(TransferRequest.DIRECTION_UPLOAD, request.direction)
        assertEquals(77L, request.token)
        assertEquals("Music\\Artist\\track.flac", request.filename)
        assertEquals(1_234_567L, request.size)
    }

    @Test
    fun `a legacy download request has no size`() {
        val body = MessageWriter()
            .writeUInt32(TransferRequest.DIRECTION_DOWNLOAD)
            .writeUInt32(5)
            .writeString("track.flac")
            .toByteArray()

        val request = TransferRequest.parse(body)

        assertEquals(TransferRequest.DIRECTION_DOWNLOAD, request.direction)
        assertNull(request.size)
    }

    @Test
    fun `acceptance is just the token and a flag`() {
        val body = TransferResponse.accepted(token = 0xABCD)

        // Deliberately no file size — see TransferResponse.accepted.
        assertEquals(5, body.size)

        val reader = MessageReader(body)
        assertEquals(0xABCDL, reader.readUInt32())
        assertTrue(reader.readBool())
        assertEquals(0, reader.remaining)
    }

    @Test
    fun `rejection carries the reason`() {
        val body = TransferResponse.rejected(token = 9, reason = "Cancelled")

        val reader = MessageReader(body)
        assertEquals(9L, reader.readUInt32())
        assertFalse(reader.readBool())
        assertEquals("Cancelled", reader.readString())
    }
}
