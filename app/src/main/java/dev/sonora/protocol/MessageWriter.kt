package dev.sonora.protocol

import java.io.ByteArrayOutputStream

/**
 * Writes the Soulseek protocol's primitive types.
 *
 * All integers are little-endian, and every variable-length value is prefixed with a
 * uint32 byte count. uint32 and uint64 are exposed as [Long] and treated as unsigned, so
 * values above 2^31 are representable.
 */
class MessageWriter {

    private val out = ByteArrayOutputStream()

    fun writeByte(value: Int): MessageWriter = apply { out.write(value and 0xFF) }

    fun writeUInt16(value: Int): MessageWriter = apply {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    fun writeUInt32(value: Long): MessageWriter = apply {
        repeat(4) { out.write(((value ushr (it * 8)) and 0xFF).toInt()) }
    }

    fun writeUInt64(value: Long): MessageWriter = apply {
        repeat(8) { out.write(((value ushr (it * 8)) and 0xFF).toInt()) }
    }

    fun writeBool(value: Boolean): MessageWriter = writeByte(if (value) 1 else 0)

    /**
     * Length-prefixed string, encoded as ISO-8859-1 so every byte round-trips unchanged.
     * Filenames are a separate concern: real clients send them as UTF-8, so callers should
     * decode from [readBytes] rather than trusting [MessageReader.readString].
     */
    fun writeString(value: String): MessageWriter =
        writeBytes(value.toByteArray(Charsets.ISO_8859_1))

    /** Length-prefixed byte array. */
    fun writeBytes(value: ByteArray): MessageWriter = apply {
        writeUInt32(value.size.toLong())
        out.write(value, 0, value.size)
    }

    /** Appends bytes with no length prefix. */
    fun writeRaw(value: ByteArray): MessageWriter = apply { out.write(value, 0, value.size) }

    fun toByteArray(): ByteArray = out.toByteArray()
}
