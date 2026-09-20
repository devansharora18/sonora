package dev.sonora.protocol.server

import dev.sonora.protocol.Framing
import dev.sonora.protocol.Message
import dev.sonora.protocol.MessageWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uses a loopback socket pair standing in for the server, so the read loop is exercised for
 * real rather than mocked.
 */
class ServerConnectionTest {

    @Test
    fun `delivers messages in order`() {
        withConnection { connection, peer ->
            val received = LinkedBlockingQueue<Message>()
            connection.startReading { received.put(it) }

            write(peer, code = 64)
            write(peer, code = 83)
            write(peer, code = 84)

            assertEquals(64L, received.poll(5, TimeUnit.SECONDS)?.code)
            assertEquals(83L, received.poll(5, TimeUnit.SECONDS)?.code)
            assertEquals(84L, received.poll(5, TimeUnit.SECONDS)?.code)
        }
    }

    @Test
    fun `carries the message body through`() {
        withConnection { connection, peer ->
            val received = LinkedBlockingQueue<Message>()
            connection.startReading { received.put(it) }

            write(peer, code = ConnectToPeer.CODE, body = ConnectToPeerTestBody.peerAddress())

            val message = received.poll(5, TimeUnit.SECONDS) ?: error("nothing delivered")
            assertEquals(ConnectToPeer.CODE, message.code)

            val address = ConnectToPeer.parse(message.body)
            assertEquals("remote_peer", address.username)
            assertEquals(2242L, address.port)
        }
    }

    @Test
    fun `a throwing handler does not end the session`() {
        withConnection { connection, peer ->
            val delivered = AtomicInteger()
            val latch = CountDownLatch(2)

            connection.startReading {
                val count = delivered.incrementAndGet()
                latch.countDown()
                if (count == 1) throw IllegalStateException("handler blew up")
            }

            write(peer, code = 64)
            write(peer, code = 83)

            assertTrue("second message never arrived", latch.await(5, TimeUnit.SECONDS))
            assertEquals(2, delivered.get())
        }
    }

    @Test
    fun `stops when the peer closes the socket`() {
        withConnection { connection, peer ->
            val received = LinkedBlockingQueue<Message>()
            connection.startReading { received.put(it) }

            write(peer, code = 64)
            assertEquals(64L, received.poll(5, TimeUnit.SECONDS)?.code)

            peer.close()

            // The loop should have exited rather than delivering anything further.
            assertNull(received.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    private fun write(socket: Socket, code: Long, body: ByteArray = ByteArray(0)) {
        socket.getOutputStream().write(Framing.SERVER.encode(code, body))
        socket.getOutputStream().flush()
    }

    private fun withConnection(block: (ServerConnection, Socket) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                server.accept().use { peer ->
                    ServerConnection(client).use { connection ->
                        block(connection, peer)
                    }
                }
            }
        }
    }
}

/** Small body builder kept out of the test body for readability. */
private object ConnectToPeerTestBody {
    fun peerAddress(): ByteArray = MessageWriter()
        .writeString("remote_peer")
        .writeString("P")
        .writeUInt32((136L shl 24) or (233L shl 16) or (9L shl 8) or 106L)
        .writeUInt32(2242)
        .writeUInt32(0xABCD)
        .writeBool(false)
        .writeUInt32(0)
        .writeUInt32(0)
        .toByteArray()
}
