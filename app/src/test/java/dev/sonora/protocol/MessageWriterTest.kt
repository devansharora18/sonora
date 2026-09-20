package dev.sonora.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageWriterTest {

    @Test
    fun `integers are written little-endian`() {
        val bytes = MessageWriter()
            .writeUInt16(0x1234)
            .writeUInt32(0x12345678L)
            .writeUInt64(0x0102030405060708L)
            .toByteArray()

        assertArrayEquals(
            byteArrayOf(
                0x34, 0x12,
                0x78, 0x56, 0x34, 0x12,
                0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01,
            ),
            bytes,
        )
    }

    @Test
    fun `uint32 above 2^31 round-trips as unsigned`() {
        val value = 0xFFFF_FFFFL

        val bytes = MessageWriter().writeUInt32(value).toByteArray()

        assertEquals(value, MessageReader(bytes).readUInt32())
    }

    @Test
    fun `string is length-prefixed and round-trips`() {
        val bytes = MessageWriter().writeString("username").toByteArray()

        assertEquals(12, bytes.size) // uint32 length + 8 bytes
        assertEquals(8L, MessageReader(bytes).readUInt32())
        assertEquals("username", MessageReader(bytes).readString())
    }

    @Test
    fun `bool round-trips`() {
        assertTrue(MessageReader(MessageWriter().writeBool(true).toByteArray()).readBool())
        assertFalse(MessageReader(MessageWriter().writeBool(false).toByteArray()).readBool())
    }

    @Test
    fun `all primitives round-trip together`() {
        val body = MessageWriter()
            .writeByte(0xAB)
            .writeUInt16(0xBEEF)
            .writeUInt32(0xDEAD_BEEFL)
            .writeUInt64(0x0123_4567_89AB_CDEFL)
            .writeBool(true)
            .writeString("track.flac")
            .writeBytes(byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte()))
            .toByteArray()

        val reader = MessageReader(body)

        assertEquals(0xAB, reader.readByte())
        assertEquals(0xBEEF, reader.readUInt16())
        assertEquals(0xDEAD_BEEFL, reader.readUInt32())
        assertEquals(0x0123_4567_89AB_CDEFL, reader.readUInt64())
        assertTrue(reader.readBool())
        assertEquals("track.flac", reader.readString())
        assertArrayEquals(
            byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte()),
            reader.readBytes(),
        )
        assertEquals(0, reader.remaining)
    }
}
