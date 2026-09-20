package dev.sonora.protocol.peer

import dev.sonora.protocol.MessageReader
import dev.sonora.protocol.MessageWriter
import dev.sonora.protocol.readExactly
import java.io.InputStream
import java.io.OutputStream

/**
 * The message set for an `F` (file transfer) connection.
 *
 * File messages carry **no length prefix and no code** — the connection type is the message type
 * — so they cannot go through [dev.sonora.protocol.Framing] at all. An `F` connection is:
 *
 * 1. the uploader announces the transfer with [FileTransferInit] (a token),
 * 2. we answer with [FileOffset], saying how many bytes we already have,
 * 3. the uploader streams raw bytes until the file is complete,
 * 4. we close the connection.
 *
 * The token is the same one from the [TransferRequest] we accepted, which is how a transfer is
 * matched to the download that asked for it.
 */
object FileTransfer {

    /** Peer code-less `FileTransferInit`. Uploader → downloader. */
    object Init {
        const val BYTES = 4

        fun encode(token: Long): ByteArray = MessageWriter().writeUInt32(token).toByteArray()

        fun parse(bytes: ByteArray): Long = MessageReader(bytes).readUInt32()
    }

    /** Peer code-less `FileOffset`. Downloader → uploader, telling it where to resume. */
    object Offset {
        const val BYTES = 8

        fun encode(offset: Long): ByteArray = MessageWriter().writeUInt64(offset).toByteArray()

        fun parse(bytes: ByteArray): Long = MessageReader(bytes).readUInt64()
    }

    /** Reads the token the uploader announces when it opens the connection. */
    fun readInitToken(input: InputStream): Long =
        Init.parse(input.readExactly(Init.BYTES))

    /** Tells the uploader where to resume from. Zero means a fresh download. */
    fun requestFrom(output: OutputStream, offset: Long) {
        output.write(Offset.encode(offset))
        output.flush()
    }

    /**
     * Streams [length] bytes into [destination], returning how many were copied.
     *
     * A short count means the uploader hung up early, which the caller should treat as a failed
     * or resumable transfer rather than a complete one.
     */
    fun copyBytes(input: InputStream, destination: OutputStream, length: Long): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var copied = 0L

        while (copied < length) {
            val wanted = minOf(buffer.size.toLong(), length - copied).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) break

            destination.write(buffer, 0, read)
            copied += read
        }

        destination.flush()
        return copied
    }

    private const val BUFFER_BYTES = 64 * 1024
}
