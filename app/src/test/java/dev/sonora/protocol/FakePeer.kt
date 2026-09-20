package dev.sonora.protocol

import dev.sonora.protocol.peer.FileSearchResponse
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
) : Closeable {

    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val live = AtomicInteger()
    private val peak = AtomicInteger()
    private val release = CountDownLatch(1)
    private val handshakeTokens = ConcurrentHashMap.newKeySet<Long>()

    val port: Int get() = server.localPort

    /** The most connections that were ever open at the same moment. */
    val peakConcurrency: Int get() = peak.get()

    /** Tokens seen in `PierceFireWall` handshakes. */
    val tokens: Set<Long> get() = handshakeTokens

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
            val token = MessageReader(handshake.body).readUInt32()
            handshakeTokens += token

            reply(token)?.let { body ->
                Framing.PEER.write(socket.getOutputStream(), FileSearchResponse.CODE, body)
            }

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
