package dev.sonora.protocol.peer

import dev.sonora.protocol.Framing
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Accepts inbound peer connections and completes the `PeerInit` handshake.
 *
 * Search results arrive this way: a peer holding a match resolves our address through the
 * server and connects *to us*. Peers are strangers on the public internet, so unlike the
 * local API this binds every interface rather than loopback.
 *
 * The port must match whatever [dev.sonora.protocol.server.SetWaitPort] advertises, or no
 * peer will be able to reach us.
 */
class PeerListener(
    port: Int,
    private val onPeer: (PeerSession) -> Unit,
) : Closeable {

    private val server = ServerSocket(port, BACKLOG, InetAddress.getByName("0.0.0.0"))

    @Volatile
    private var closed = false

    /** The port actually bound — differs from the requested one when 0 was passed. */
    val boundPort: Int get() = server.localPort

    init {
        thread(name = "sonora-peer-listener", isDaemon = true) { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return // listener closed
            }

            // A peer that connects and then stalls would otherwise hold the connection
            // forever. Bound the handshake.
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS

            // Anything can arrive on a socket a stranger opened, so this parse must never
            // escape: one malformed peer would otherwise kill the accept loop for everyone.
            val handshake = try {
                val message = Framing.PEER_INIT.read(socket.getInputStream())
                if (message.code == PeerInit.CODE) PeerInit.parse(message.body) else null
            } catch (_: Exception) {
                null
            }

            if (handshake == null) {
                socket.close()
            } else {
                onPeer(PeerSession(handshake.username, handshake.connectionType, socket))
            }
        }
    }

    override fun close() {
        closed = true
        server.close()
    }

    private companion object {
        const val BACKLOG = 50
        const val HANDSHAKE_TIMEOUT_MS = 30_000
    }
}
