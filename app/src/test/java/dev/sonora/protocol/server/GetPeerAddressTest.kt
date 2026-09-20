package dev.sonora.protocol.server

import dev.sonora.protocol.MessageReader
import dev.sonora.protocol.MessageWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class GetPeerAddressTest {

    @Test
    fun `request carries the username`() {
        val body = GetPeerAddress.request("some_peer")

        assertEquals("some_peer", MessageReader(body).readString())
    }

    @Test
    fun `parses the resolved address`() {
        val ip = (136L shl 24) or (233L shl 16) or (9L shl 8) or 106L

        val body = MessageWriter()
            .writeString("some_peer")
            .writeUInt32(ip)
            .writeUInt32(2242)
            .writeUInt32(0) // obfuscation type
            .writeUInt16(0) // obfuscated port
            .toByteArray()

        val address = GetPeerAddress.parse(body)

        assertEquals("some_peer", address.username)
        assertEquals(ip, address.ip)
        assertEquals(2242L, address.port)
        assertEquals("136.233.9.106", address.ipAddress())
    }

    @Test
    fun `tolerates a response with no obfuscation fields`() {
        val body = MessageWriter()
            .writeString("some_peer")
            .writeUInt32(1)
            .writeUInt32(2)
            .toByteArray()

        val address = GetPeerAddress.parse(body)

        assertEquals("some_peer", address.username)
        assertEquals(2L, address.port)
    }
}
