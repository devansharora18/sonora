package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageWriter

/**
 * Peer code 43. Tells a peer we want a file; they reply with a [TransferRequest] once ready to
 * send it.
 *
 * Carries **only the file path — no token**. The prose spec lists one, but the reference
 * implementation sends just the string, so adding a token here would break compatibility.
 */
object QueueUpload {

    const val CODE = 43L

    fun request(filename: String): ByteArray =
        MessageWriter().writeString(filename).toByteArray()
}
