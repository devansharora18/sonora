package dev.sonora.protocol

import dev.sonora.protocol.peer.SearchResponse
import dev.sonora.protocol.peer.SearchWire
import dev.sonora.protocol.server.FileSearch
import dev.sonora.protocol.server.LoginResponse
import dev.sonora.protocol.server.SetStatus
import dev.sonora.protocol.server.SetWaitPort
import dev.sonora.protocol.server.SharedFoldersFiles
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [SoulseekSession] against [FakeSoulseekServer], so it is covered without credentials,
 * a network, or the live spike.
 */
class SoulseekSessionTest {

    @Test
    fun `connect logs in and performs the session handshake`() {
        FakeSoulseekServer().use { server ->
            session(server).use { session ->
                val response = session.connect()

                assertEquals(FakeSoulseekServer.DEFAULT_RESPONSE, response)
                assertTrue(session.isConnected)

                // The three session messages must follow login, in this order.
                assertEquals(SetWaitPort.CODE, server.next().code)
                assertEquals(SetStatus.CODE, server.next().code)
                assertEquals(SharedFoldersFiles.CODE, server.next().code)
            }
        }
    }

    @Test
    fun `connect sends the supplied credentials`() {
        FakeSoulseekServer().use { server ->
            session(server).use { it.connect() }

            val reader = MessageReader(checkNotNull(server.loginBody))
            assertEquals("test_user", reader.readString())
            assertEquals("test_password", reader.readString())
        }
    }

    @Test
    fun `a rejected login is returned rather than thrown`() {
        val rejection = LoginResponse.Rejected("INVALIDPASS", null)

        FakeSoulseekServer(loginResponse = rejection).use { server ->
            session(server).use { session ->
                assertEquals(rejection, session.connect())
                assertFalse(session.isConnected)
            }
        }
    }

    @Test
    fun `an invalid-username rejection carries its detail through`() {
        val rejection = LoginResponse.Rejected("INVALIDUSERNAME", "Nick too long.")

        FakeSoulseekServer(loginResponse = rejection).use { server ->
            session(server).use { session ->
                assertEquals(rejection, session.connect())
            }
        }
    }

    @Test
    fun `search sends the query tagged with the token it returns`() {
        FakeSoulseekServer().use { server ->
            session(server).use { session ->
                session.connect()
                val token = session.search("aphex twin") { }

                val search = server.await(FileSearch.CODE)
                val reader = MessageReader(search.body)

                assertEquals(token, reader.readUInt32())
                assertEquals("aphex twin", reader.readString())
            }
        }
    }

    @Test
    fun `a relayed peer is dialled and its results routed to the search`() {
        // The peer answers the dial-back handshake with a search result.
        FakePeer(
            reply = { token ->
                SearchWire.zlib(
                    SearchWire.searchResponseBody(
                        username = "remote_peer",
                        token = token,
                        files = listOf(SearchWire.fileEntry("track.flac", 1234, emptyMap())),
                    ),
                )
            },
        ).use { peer ->
            FakeSoulseekServer().use { server ->
                session(server).use { session ->
                    session.connect()

                    val results = LinkedBlockingQueue<SearchResponse>()
                    val token = session.search("query") { results += it }

                    // The server says a peer could not reach us and expects us to dial out.
                    server.relay("remote_peer", peer.port, token)

                    val response = results.poll(5, TimeUnit.SECONDS)
                        ?: error("no search response was routed to the callback")

                    assertEquals(token, response.token)
                    assertEquals("remote_peer", response.username)

                    val file = response.files.single()
                    assertEquals("track.flac", file.filename)
                    assertEquals(1234L, file.size)

                    assertTrue("peer never saw the handshake", peer.tokens.contains(token))
                }
            }
        }
    }

    @Test
    fun `never dials more peers at once than the configured ceiling`() {
        val limit = 3
        val relays = 12

        // Holding connections open is what makes concurrent dials observable at all.
        FakePeer(holdOpen = true).use { peer ->
            FakeSoulseekServer().use { server ->
                session(server, maxConcurrentPeers = limit).use { session ->
                    session.connect()
                    session.search("query") { }

                    repeat(relays) { server.relay("peer$it", peer.port, token = 1L) }

                    assertTrue(
                        "expected the pool to reach $limit concurrent dials",
                        peer.awaitConcurrency(limit, timeoutMillis = 10_000),
                    )

                    // A single assertion covers both halves: reaching the ceiling, and never
                    // exceeding it. A larger peak fails here just as a smaller one does.
                    assertEquals(limit, peer.peakConcurrency)
                }
            }
        }
    }

    private fun session(
        server: FakeSoulseekServer,
        maxConcurrentPeers: Int = SoulseekSession.DEFAULT_MAX_CONCURRENT_PEERS,
    ) = SoulseekSession(
        username = "test_user",
        password = "test_password",
        host = "127.0.0.1",
        port = server.port,
        // Ephemeral: the peer listener must not collide with anything on the test machine.
        listenPort = 0,
        maxConcurrentPeers = maxConcurrentPeers,
    )
}
