package dev.sonora.protocol.peer

import dev.sonora.protocol.Framing
import dev.sonora.protocol.MessageWriter
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerListenerTest {

    @Test
    fun `completes a handshake then reads a search response over the same connection`() {
        val sessions = LinkedBlockingQueue<PeerSession>()

        PeerListener(port = 0, onPeer = sessions::put).use { listener ->
            Socket(InetAddress.getLoopbackAddress(), listener.boundPort).use { client ->
                val out = client.getOutputStream()

                Framing.PEER_INIT.write(
                    out,
                    PeerInit.CODE,
                    MessageWriter()
                        .writeString("remote_peer")
                        .writeString(PeerInit.TYPE_PEER)
                        .writeUInt32(0)
                        .toByteArray(),
                )

                val session = sessions.poll(5, TimeUnit.SECONDS)
                    ?: error("listener never reported the peer")
                assertEquals("remote_peer", session.username)
                assertEquals(PeerInit.TYPE_PEER, session.connectionType)

                // The peer now answers our search on the connection it opened.
                Framing.PEER.write(
                    out,
                    FileSearchResponse.CODE,
                    SearchWire.zlib(
                        SearchWire.searchResponseBody(
                            username = "remote_peer",
                            token = 42,
                            files = listOf(SearchWire.fileEntry("a.flac", 123, emptyMap())),
                        ),
                    ),
                )

                val message = session.read()

                assertEquals(FileSearchResponse.CODE, message.code)
                val response = FileSearchResponse.parse(message.body)
                assertEquals("remote_peer", response.username)
                assertEquals(42L, response.token)
                assertEquals("a.flac", response.files.single().filename)
                assertEquals(123L, response.files.single().size)
            }
        }
    }

    @Test
    fun `drops a connection whose first message is not a peer init`() {
        val sessions = LinkedBlockingQueue<PeerSession>()

        PeerListener(port = 0, onPeer = sessions::put).use { listener ->
            Socket(InetAddress.getLoopbackAddress(), listener.boundPort).use { client ->
                // A well-formed handshake body under the wrong code, so this fails only if the
                // code is actually checked rather than the body merely being unparseable.
                Framing.PEER_INIT.write(
                    client.getOutputStream(),
                    99L,
                    MessageWriter()
                        .writeString("remote_peer")
                        .writeString(PeerInit.TYPE_PEER)
                        .writeUInt32(0)
                        .toByteArray(),
                )

                assertNull("no session should be reported", sessions.poll(500, TimeUnit.MILLISECONDS))

                // The listener should have closed the socket.
                client.soTimeout = 2_000
                assertEquals(-1, client.getInputStream().read())
            }
        }
    }

    @Test
    fun `survives a malformed handshake and keeps accepting`() {
        val sessions = LinkedBlockingQueue<PeerSession>()

        PeerListener(port = 0, onPeer = sessions::put).use { listener ->
            // A PeerInit frame whose body is far too short to parse.
            Socket(InetAddress.getLoopbackAddress(), listener.boundPort).use { client ->
                Framing.PEER_INIT.write(client.getOutputStream(), PeerInit.CODE, byteArrayOf(0x01))
            }

            assertNull(sessions.poll(500, TimeUnit.MILLISECONDS))

            // The listener must still be alive for the next peer.
            Socket(InetAddress.getLoopbackAddress(), listener.boundPort).use { client ->
                Framing.PEER_INIT.write(
                    client.getOutputStream(),
                    PeerInit.CODE,
                    MessageWriter()
                        .writeString("later_peer")
                        .writeString(PeerInit.TYPE_PEER)
                        .writeUInt32(0)
                        .toByteArray(),
                )

                val session = sessions.poll(5, TimeUnit.SECONDS)
                    ?: error("listener died after a malformed handshake")
                assertEquals("later_peer", session.username)
            }
        }
    }
}
