package dev.sonora.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * How messages are delimited on each Soulseek connection type.
 *
 * Every type except file transfers prefixes the message with a uint32 length, but the
 * message code width differs. Getting this wrong means nothing else works — see
 * `docs/protocol-scope.md`.
 */
enum class Framing(val codeSize: Int, val lengthPrefixed: Boolean) {
    /** Server messages: `uint32 length | uint32 code | payload`. */
    SERVER(4, true),

    /** Messages over a `P` connection: `uint32 length | uint32 code | payload`. */
    PEER(4, true),

    /** Peer init messages: `uint32 length | uint8 code | payload`. */
    PEER_INIT(1, true),

    /** Messages over a `D` connection: `uint32 length | uint8 code | payload`. */
    DISTRIBUTED(1, true),

    /** File transfers over an `F` connection: raw payload, no length and no code. */
    FILE(0, false),
    ;

    fun encode(code: Long, body: ByteArray): ByteArray {
        require(lengthPrefixed) { "$name messages are not framed" }

        val payload = MessageWriter()
        if (codeSize == 1) payload.writeByte(code.toInt()) else payload.writeUInt32(code)
        payload.writeRaw(body)

        val payloadBytes = payload.toByteArray()
        return MessageWriter()
            .writeUInt32(payloadBytes.size.toLong())
            .writeRaw(payloadBytes)
            .toByteArray()
    }

    /** Decodes a complete frame, length prefix included. */
    fun decode(frame: ByteArray): Message {
        require(lengthPrefixed) { "$name messages are not framed" }

        val reader = MessageReader(frame)
        val declaredLength = reader.readUInt32()
        require(declaredLength == reader.remaining.toLong()) {
            "frame declares $declaredLength bytes but carries ${reader.remaining}"
        }

        val code = if (codeSize == 1) reader.readByte().toLong() else reader.readUInt32()
        return Message(code, reader.readRemaining())
    }

    fun write(output: OutputStream, code: Long, body: ByteArray) {
        output.write(encode(code, body))
        output.flush()
    }

    /** Reads one framed message, blocking until it arrives. */
    fun read(input: InputStream): Message {
        require(lengthPrefixed) { "$name messages are not framed" }

        val declaredLength = MessageReader(input.readExactly(4)).readUInt32()
        require(declaredLength <= MAX_MESSAGE_LENGTH) {
            "message length $declaredLength exceeds the $MAX_MESSAGE_LENGTH byte limit"
        }

        val payload = input.readExactly(declaredLength.toInt())
        val reader = MessageReader(payload)
        val code = if (codeSize == 1) reader.readByte().toLong() else reader.readUInt32()
        return Message(code, reader.readRemaining())
    }

    companion object {
        /**
         * Safety bound on a single message, so a corrupt or hostile length prefix cannot
         * force a huge allocation. Comfortably fits a SharedFileListResponse from a very
         * large library; raise it if a legitimate message is ever rejected.
         */
        const val MAX_MESSAGE_LENGTH = 32L * 1024 * 1024
    }
}

/**
 * Reads exactly [count] bytes.
 *
 * [InputStream.read] is allowed to return fewer bytes than requested, so a single call is
 * not enough. This is where naive protocol parsers silently desync from the wire.
 */
internal fun InputStream.readExactly(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(bytes, offset, count - offset)
        if (read < 0) throw EOFException("stream ended after $offset of $count bytes")
        offset += read
    }
    return bytes
}

/** A decoded message: its numeric code and the raw body following it. */
class Message(val code: Long, val body: ByteArray) {

    override fun equals(other: Any?): Boolean =
        other is Message && code == other.code && body.contentEquals(other.body)

    override fun hashCode(): Int = 31 * code.hashCode() + body.contentHashCode()

    override fun toString(): String = "Message(code=$code, ${body.size} byte body)"
}
