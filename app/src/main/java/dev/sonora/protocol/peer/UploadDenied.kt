package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageReader
import dev.sonora.protocol.MessageWriter

/**
 * Peer code 50. Rejects a `QueueUpload`, or revokes a file that was previously queued.
 *
 * Sent on the connection the file was requested on, which is why that connection has to stay
 * readable after we accept.
 */
object UploadDenied {

    const val CODE = 50L

    fun parse(body: ByteArray): Denial {
        val reader = MessageReader(body)
        return Denial(
            filename = reader.readString().replace('/', '\\'),
            reason = reader.readString(),
        )
    }

    /** Tells a peer we will not send the file it asked for. */
    fun deny(filename: String, reason: String): ByteArray =
        MessageWriter().writeString(filename).writeString(reason).toByteArray()
}

data class Denial(val filename: String, val reason: String)
