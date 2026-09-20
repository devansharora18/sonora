package dev.sonora.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the Soulseek protocol's primitive types. Mirror of [MessageWriter].
 *
 * Strings decode as ISO-8859-1 for byte-exact round-tripping; see
 * [MessageWriter.writeString] for why filenames need different handling.
 */
class MessageReader(private val buffer: ByteBuffer) {

    constructor(bytes: ByteArray) : this(ByteBuffer.wrap(bytes))

    init {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
    }

    val remaining: Int get() = buffer.remaining()

    fun readByte(): Int = buffer.get().toInt() and 0xFF

    fun readUInt16(): Int = buffer.short.toInt() and 0xFFFF

    fun readUInt32(): Long = buffer.int.toLong() and 0xFFFF_FFFFL

    fun readUInt64(): Long = buffer.long

    fun readBool(): Boolean = readByte() != 0

    fun readBytes(): ByteArray {
        val bytes = ByteArray(readUInt32().toInt())
        buffer.get(bytes)
        return bytes
    }

    fun readString(): String = String(readBytes(), Charsets.ISO_8859_1)

    /** Byte at an offset ahead of the cursor, without consuming it. */
    fun byteAt(offset: Int): Int = buffer.get(buffer.position() + offset).toInt() and 0xFF

    fun skip(count: Int) {
        buffer.position(buffer.position() + count)
    }

    /** Everything left in the buffer. */
    fun readRemaining(): ByteArray {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }
}
