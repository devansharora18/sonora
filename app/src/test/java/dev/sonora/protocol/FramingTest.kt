package dev.sonora.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FramingTest {

    @Test
    fun `server frame prefixes payload length and uses a uint32 code`() {
        val frame = Framing.SERVER.encode(code = 1, body = byteArrayOf(0x0A, 0x0B))

        assertArrayEquals(
            byteArrayOf(
                0x06, 0x00, 0x00, 0x00, // 6 bytes follow
                0x01, 0x00, 0x00, 0x00, // code 1 as uint32
                0x0A, 0x0B,
            ),
            frame,
        )
    }

    @Test
    fun `peer init frame uses a single-byte code`() {
        val frame = Framing.PEER_INIT.encode(code = 1, body = byteArrayOf(0x0A))

        assertArrayEquals(
            byteArrayOf(
                0x02, 0x00, 0x00, 0x00, // 2 bytes follow
                0x01, // code 1 as uint8
                0x0A,
            ),
            frame,
        )
    }

    @Test
    fun `every length-prefixed framing round-trips`() {
        val body = byteArrayOf(0x00, 0x01, 0x02, 0x7F, 0x80.toByte(), 0xFF.toByte())

        for (framing in listOf(Framing.SERVER, Framing.PEER, Framing.PEER_INIT, Framing.DISTRIBUTED)) {
            val decoded = framing.decode(framing.encode(code = 26, body = body))
            assertEquals(framing.name, 26L, decoded.code)
            assertArrayEquals(framing.name, body, decoded.body)
        }
    }

    @Test
    fun `empty body round-trips`() {
        val decoded = Framing.SERVER.decode(Framing.SERVER.encode(code = 32, body = ByteArray(0)))

        assertEquals(32L, decoded.code)
        assertEquals(0, decoded.body.size)
    }

    @Test
    fun `file messages are not framed`() {
        assertThrows(IllegalArgumentException::class.java) {
            Framing.FILE.encode(code = 1, body = byteArrayOf(0x0A))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Framing.FILE.decode(byteArrayOf(0x0A))
        }
    }

    @Test
    fun `decode rejects a length that disagrees with the frame`() {
        val frame = Framing.SERVER.encode(code = 1, body = byteArrayOf(0x0A))
        val truncated = frame.copyOf(frame.size - 1)

        assertThrows(IllegalArgumentException::class.java) { Framing.SERVER.decode(truncated) }
    }
}
