package dev.sonora.protocol.peer

import dev.sonora.protocol.Framing
import dev.sonora.protocol.Message
import java.io.Closeable
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

    override fun close() {
        socket.close()
    }
}
