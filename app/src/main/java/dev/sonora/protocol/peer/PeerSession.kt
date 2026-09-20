package dev.sonora.protocol.peer

import dev.sonora.protocol.Framing
import dev.sonora.protocol.Message
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** A peer connection that has completed its [PeerInit] handshake. */
class PeerSession(
    val username: String,
    /** `P`, `F` or `D` — see [PeerInit.TYPE_PEER] and friends. */
    val connectionType: String,
    private val socket: Socket,
) : Closeable {

    /** Blocks until the peer sends its next message. */
    fun read(): Message = Framing.PEER.read(socket.getInputStream())

    /** Sends a peer message. */
    fun send(code: Long, body: ByteArray) {
        Framing.PEER.write(socket.getOutputStream(), code, body)
    }

    /**
     * Raw streams, for the file-transfer path. File messages use their own framing — no length
     * prefix and no code — so [read] and [send] do not apply there.
     */
    fun inputStream(): InputStream = socket.getInputStream()

    fun outputStream(): OutputStream = socket.getOutputStream()

    /**
     * How long [read] blocks before throwing `SocketTimeoutException`; 0 waits forever.
     *
     * Callers choose this: an idle peer connection is usually dead, but the right idle window
     * depends on what the connection is for.
     */
    var readTimeoutMillis: Int
        get() = socket.soTimeout
        set(value) {
            socket.soTimeout = value
        }

    override fun close() {
        socket.close()
    }
}
