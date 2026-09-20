package dev.sonora.protocol

import dev.sonora.protocol.peer.FileSearchResponse
import dev.sonora.protocol.peer.PeerInit
import dev.sonora.protocol.peer.PeerSession
import dev.sonora.protocol.peer.PierceFireWall
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A loopback stand-in for a peer node.
 *
 * Accepts outbound dials, reads the `PierceFireWall` handshake, and optionally answers with a
 * peer message. Setting [holdOpen] keeps connections alive, which is what makes *concurrent*
 * dials observable — the only way to assert the session's connection ceiling.
 */
internal class FakePeer(
    /** Peer message body to answer with, given the handshake token; null to send nothing. */
    private val reply: (token: Long) -> ByteArray? = { null },
    private val holdOpen: Boolean = false,
    /**
     * Post-handshake peer exchange, for tests that need a conversation. Runs instead of nothing;
     * [reply] and [holdOpen] still apply.
     */
    private val conversation: ((PeerSession) -> Unit)? = null,
) : Closeable {

    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val live = AtomicInteger()
    private val peak = AtomicInteger()
    private val release = CountDownLatch(1)
    private val handshakeTokens = ConcurrentHashMap.newKeySet<Long>()
    private val connectionTypes = ConcurrentHashMap.newKeySet<String>()

    val port: Int get() = server.localPort

    /** The most connections that were ever open at the same moment. */
    val peakConcurrency: Int get() = peak.get()

    /** Tokens seen in `PierceFireWall` handshakes. */
    val tokens: Set<Long> get() = handshakeTokens

    /** Connection types seen in direct `PeerInit` handshakes — `P`, `F` or `D`. */
    val directConnectionTypes: Set<String> get() = connectionTypes

    init {
        thread(isDaemon = true, name = "fake-peer") { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                return
            }

            sockets += socket

            // Incremented before the peak update: updateAndGet may retry its lambda, so doing
            // the increment inside it would count the same connection more than once.
            val now = live.incrementAndGet()
            peak.updateAndGet { current -> maxOf(current, now) }

            thread(isDaemon = true) { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            val handshake = Framing.PEER_INIT.read(socket.getInputStream())

            var username = ""
            var connectionType = ""

            val token: Long = when (handshake.code) {
                // Indirect: the peer dialled us back after we sent PierceFireWall.
                PierceFireWall.CODE -> MessageReader(handshake.body).readUInt32()

                // Direct: the session dialled us.
                PeerInit.CODE -> {
                    val reader = MessageReader(handshake.body)
                    username = reader.readString()
                    connectionType = reader.readString()
                    connectionTypes += connectionType
                    reader.readUInt32()
                }

                else -> return
            }

            handshakeTokens += token

            reply(token)?.let { body ->
                Framing.PEER.write(socket.getOutputStream(), FileSearchResponse.CODE, body)
            }

            conversation?.invoke(PeerSession(username, connectionType, socket))

            if (holdOpen) {
                release.await()
            }
        } catch (_: Exception) {
            // Session closed, or the peer said nothing.
        } finally {
            live.decrementAndGet()
            sockets -= socket
            socket.close()
        }
    }

    /** Waits until at least [count] connections have been open simultaneously. */
    fun awaitConcurrency(count: Int, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline && peak.get() < count) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return peak.get() >= count
    }

    /**
     * Waits until a direct handshake of [type] has been seen. The peer records handshakes on its
     * own thread, so a caller that has just dialled must wait rather than assert immediately.
     */
    fun awaitConnectionType(type: String, timeoutMillis: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline && !connectionTypes.contains(type)) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return connectionTypes.contains(type)
    }

    override fun close() {
        release.countDown()
        sockets.forEach { it.close() }
        server.close()
    }

    private companion object {
        const val BACKLOG = 200
        const val POLL_INTERVAL_MS = 20L
    }
}
