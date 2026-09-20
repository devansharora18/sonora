package dev.sonora.protocol.peer

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
     * Deliberately carries **no file size**. The prose spec lists one for a download response,
     * but the reference implementation omits it and parses its own absence with a
     * has-remaining-content guard — so sending a size risks disagreeing with every client that
     * follows the implementation.
     */
    fun accepted(token: Long): ByteArray =
        MessageWriter()
            .writeUInt32(token)
            .writeBool(true)
            .toByteArray()

    fun rejected(token: Long, reason: String): ByteArray =
        MessageWriter()
            .writeUInt32(token)
            .writeBool(false)
            .writeString(reason)
            .toByteArray()
}
