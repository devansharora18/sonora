package dev.sonora.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FramingStreamTest {

    @Test
    fun `write then read round-trips through streams`() {
        val out = ByteArrayOutputStream()
        Framing.SERVER.write(out, code = 26, body = byteArrayOf(0x01, 0x02, 0x03))

        val message = Framing.SERVER.read(ByteArrayInputStream(out.toByteArray()))

        assertEquals(26L, message.code)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), message.body)
    }

    @Test
    fun `reads the reference login frame from a stream`() {
        val frame = hex(
            "48 00 00 00 01 00 00 00 08 00 00 00 75 73 65 72 6e 61 6d 65 08 00 00 00 " +
                "70 61 73 73 77 6f 72 64 b1 00 00 00 20 00 00 00 64 35 31 63 39 61 37 65 " +
                "39 33 35 33 37 34 36 61 36 30 32 30 66 39 36 30 32 64 34 35 32 39 32 39 " +
                "01 00 00 00",
        )

        val message = Framing.SERVER.read(ByteArrayInputStream(frame))

        assertEquals(1L, message.code)
        assertEquals(68, message.body.size) // 72 bytes framed, minus the 4-byte code
        assertEquals("username", MessageReader(message.body).readString())
    }

    @Test
    fun `tolerates a stream that returns one byte at a time`() {
        val out = ByteArrayOutputStream()
        Framing.SERVER.write(out, code = 7, body = ByteArray(50) { it.toByte() })

        // InputStream.read may legitimately return fewer bytes than asked for.
        val dribbling = object : FilterInputStream(ByteArrayInputStream(out.toByteArray())) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, 1)
        }

        val message = Framing.SERVER.read(dribbling)

        assertEquals(7L, message.code)
        assertArrayEquals(ByteArray(50) { it.toByte() }, message.body)
    }

    @Test
    fun `truncated stream throws EOFException`() {
        val out = ByteArrayOutputStream()
        Framing.SERVER.write(out, code = 1, body = ByteArray(10))

        val truncated = out.toByteArray().copyOf(6) // header plus a partial payload

        assertThrows(EOFException::class.java) {
            Framing.SERVER.read(ByteArrayInputStream(truncated))
        }
    }

    @Test
    fun `rejects an oversized declared length before allocating`() {
        val lengthPrefix = MessageWriter()
            .writeUInt32(Framing.MAX_MESSAGE_LENGTH + 1)
            .toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            Framing.SERVER.read(ByteArrayInputStream(lengthPrefix))
        }
    }

    @Test
    fun `file framing cannot be read`() {
        assertThrows(IllegalArgumentException::class.java) {
            Framing.FILE.read(ByteArrayInputStream(ByteArray(0)))
        }
    }

    private fun hex(value: String): ByteArray =
        value.split(" ")
            .filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
