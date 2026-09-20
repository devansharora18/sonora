package dev.sonora.protocol.server

import dev.sonora.protocol.MessageWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectToPeerTest {

    @Test
    fun `parses the relayed peer address`() {
        // 136.233.9.106 packed the way the wire does it.
        val ip = (136L shl 24) or (233L shl 16) or (9L shl 8) or 106L

        val body = MessageWriter()
            .writeString("remote_peer")
            .writeString("P")
            .writeUInt32(ip)
            .writeUInt32(2242)
            .writeUInt32(0xABCD)
            .writeBool(true)
            .writeUInt32(0) // obfuscation type
            .writeUInt32(0) // obfuscated port
            .toByteArray()

        val address = ConnectToPeer.parse(body)

        assertEquals("remote_peer", address.username)
        assertEquals("P", address.connectionType)
        assertEquals(ip, address.ip)
        assertEquals(2242L, address.port)
        assertEquals(0xABCDL, address.token)
        assertTrue(address.isPrivileged)
    }

    @Test
    fun `renders the address as a dotted quad`() {
        val address = PeerAddress(
            username = "peer",
            connectionType = "P",
            ip = (136L shl 24) or (233L shl 16) or (9L shl 8) or 106L,
            port = 1,
            token = 0,
            isPrivileged = false,
        )

        assertEquals("136.233.9.106", address.ipAddress())
    }

    @Test
    fun `tolerates a server that omits the obfuscation fields`() {
        val body = MessageWriter()
            .writeString("remote_peer")
            .writeString("P")
            .writeUInt32(1)
            .writeUInt32(2)
            .writeUInt32(3)
            .writeBool(false)
            .toByteArray()

        val address = ConnectToPeer.parse(body)

        assertEquals("remote_peer", address.username)
        assertEquals(3L, address.token)
    }
}
