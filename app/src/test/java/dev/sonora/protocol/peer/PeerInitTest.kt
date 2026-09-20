package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class PeerInitTest {

    @Test
    fun `parses the peer handshake fields`() {
        val body = MessageWriter()
            .writeString("remote_peer")
            .writeString(PeerInit.TYPE_PEER)
            .writeUInt32(0)
            .toByteArray()

        val handshake = PeerInit.parse(body)

        assertEquals("remote_peer", handshake.username)
        assertEquals(PeerInit.TYPE_PEER, handshake.connectionType)
        assertEquals(0L, handshake.token)
    }

    @Test
    fun `parses a file connection handshake`() {
        val body = MessageWriter()
            .writeString("another_peer")
            .writeString(PeerInit.TYPE_FILE)
            .writeUInt32(0)
            .toByteArray()

        assertEquals(PeerInit.TYPE_FILE, PeerInit.parse(body).connectionType)
    }
}
