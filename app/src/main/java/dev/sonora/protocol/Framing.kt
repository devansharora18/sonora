package dev.sonora.protocol

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
}

/** A decoded message: its numeric code and the raw body following it. */
class Message(val code: Long, val body: ByteArray) {

    override fun equals(other: Any?): Boolean =
        other is Message && code == other.code && body.contentEquals(other.body)

    override fun hashCode(): Int = 31 * code.hashCode() + body.contentHashCode()

    override fun toString(): String = "Message(code=$code, ${body.size} byte body)"
}
