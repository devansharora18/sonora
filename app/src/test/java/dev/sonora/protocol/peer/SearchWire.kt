package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageWriter
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Builds peer wire bytes by hand.
 *
 * Deliberately independent of the production parsers — if a test shared an encoder with the
 * code under test, a shared mistake would pass silently.
 */
internal object SearchWire {

    fun zlib(bytes: ByteArray): ByteArray {
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

    fun fileEntry(name: String, size: Long, attributes: Map<Long, Long>): ByteArray {
        val writer = MessageWriter()
            .writeByte(1)
            .writeString(name)
            .writeUInt64(size)
            .writeUInt32(0) // obsolete extension field, empty
            .writeUInt32(attributes.size.toLong())

        attributes.forEach { (code, value) -> writer.writeUInt32(code).writeUInt32(value) }

        return writer.toByteArray()
    }

    fun searchResponseBody(
        username: String,
        token: Long,
        files: List<ByteArray>,
        freeSlot: Boolean = true,
        speed: Long = 0,
        queue: Long = 0,
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
}
