package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageReader

/**
 * Peer code 46. The uploader gave up on a transfer — its file connection closed, or it could not
 * read the file.
 *
 * Sent on the connection the file was requested on. Ignoring it means waiting for a file
 * connection that is never coming.
 */
object UploadFailed {

    const val CODE = 46L

    fun parse(body: ByteArray): String =
        MessageReader(body).readString().replace('/', '\\')
}
