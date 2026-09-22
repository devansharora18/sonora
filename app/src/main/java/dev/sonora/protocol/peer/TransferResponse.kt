package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageReader
import dev.sonora.protocol.MessageWriter

/**
 * Peer code 41. Answers a [TransferRequest], accepting or rejecting it.
 *
 * This code has two shapes — a download response and an upload response — distinguished by
 * what follows the flag. See [accepted].
 */
object TransferResponse {

    const val CODE = 41L

    /**
     * Accepts the transfer.
     *
     * [size] is optional. The prose spec lists a file size for a download response, but the
     * reference implementation omits it and parses its own absence with a has-remaining-content
     * guard. Sending it matches the spec; omitting it matches Nicotine+. Both are in use, so this
     * is a parameter rather than a decision.
     */
    fun accepted(token: Long, size: Long? = null): ByteArray {
        val writer = MessageWriter().writeUInt32(token).writeBool(true)
        size?.let { writer.writeUInt64(it) }
        return writer.toByteArray()
    }

    fun rejected(token: Long, reason: String): ByteArray =
        MessageWriter()
            .writeUInt32(token)
            .writeBool(false)
            .writeString(reason)
            .toByteArray()

    /**
     * Reads the answer to a transfer request we sent.
     *
     * Trailing fields are optional and unflagged: a rejection carries a reason, an acceptance may
     * carry a size. Only the first two fields are relied on.
     */
    fun parse(body: ByteArray): TransferAnswer {
        val reader = MessageReader(body)

        val token = reader.readUInt32()
        val allowed = reader.readBool()
        val reason = if (!allowed && reader.remaining >= 4) reader.readString() else null

        return TransferAnswer(token = token, allowed = allowed, reason = reason)
    }
}

data class TransferAnswer(
    val token: Long,
    val allowed: Boolean,
    /** Only present on a rejection. */
    val reason: String?,
)
