package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageReader

/**
 * Peer code 40. Sent by a peer when it is ready to upload to us; we answer with a
 * [TransferResponse].
 *
 * The size is only present when [FileTransferRequest.direction] is [DIRECTION_UPLOAD].
 */
object TransferRequest {

    const val CODE = 40L

    /** The peer uploads to us. */
    const val DIRECTION_UPLOAD = 1L

    /** Legacy: a download request. Superseded by [QueueUpload], but slskd still sends it. */
    const val DIRECTION_DOWNLOAD = 0L

    fun parse(body: ByteArray): FileTransferRequest {
        val reader = MessageReader(body)

        val direction = reader.readUInt32()
        val token = reader.readUInt32()
        val filename = reader.readString()
        val size = if (direction == DIRECTION_UPLOAD && reader.remaining >= 8) {
            reader.readUInt64()
        } else {
            null
        }

        return FileTransferRequest(
            direction = direction,
            token = token,
            // Paths use backslash separators on the wire, normalised as elsewhere.
            filename = filename.replace('/', '\\'),
            size = size,
        )
    }
}

data class FileTransferRequest(
    val direction: Long,
    val token: Long,
    val filename: String,
    /** Absent for a legacy download request. */
    val size: Long?,
)
