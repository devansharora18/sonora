package dev.sonora.protocol

import dev.sonora.protocol.peer.FileTransfer
import dev.sonora.protocol.peer.PeerInit
import dev.sonora.protocol.peer.PeerSession
import dev.sonora.protocol.peer.QueueUpload
import dev.sonora.protocol.peer.SearchResponse
import dev.sonora.protocol.peer.SearchWire
import dev.sonora.protocol.peer.SharedFileListRequest
import dev.sonora.protocol.peer.SharedFileListResponse
import dev.sonora.protocol.peer.TransferRequest
import dev.sonora.protocol.peer.TransferResponse
import dev.sonora.protocol.peer.UploadDenied
import dev.sonora.protocol.server.FileSearch
import dev.sonora.protocol.server.LoginResponse
import dev.sonora.protocol.server.SetStatus
import dev.sonora.protocol.server.SetWaitPort
import dev.sonora.protocol.server.SharedFoldersFiles
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Drives [SoulseekSession] against [FakeSoulseekServer], so it is covered without credentials,
 * a network, or the live spike.
 */
class SoulseekSessionTest {

    @get:Rule
    val folder = TemporaryFolder()

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

    @Test
    fun `connectToUser resolves an address and dials the peer directly`() {
        FakePeer().use { peer ->
            FakeSoulseekServer().use { server ->
                server.peerPort = peer.port

                session(server).use { session ->
                    session.connect()

                    val connected = checkNotNull(session.connectToUser("some_peer")) {
                        "expected a direct peer connection"
                    }

                    assertEquals("some_peer", connected.username)
                    assertEquals(PeerInit.TYPE_PEER, connected.connectionType)

                    // A direct dial sends PeerInit, not the PierceFireWall used by the indirect
                    // fallback — so this proves which path was taken. The peer records the
                    // handshake on its own thread, hence the wait rather than a bare assert.
                    assertTrue(
                        "peer never saw a direct ${PeerInit.TYPE_PEER} handshake",
                        peer.awaitConnectionType(PeerInit.TYPE_PEER),
                    )
                    assertEquals(setOf(PeerInit.TYPE_PEER), peer.directConnectionTypes)
                }
            }
        }
    }

    @Test
    fun `requestDownload queues the file and accepts the transfer offered`() {
        val queued = LinkedBlockingQueue<String>()
        val response = LinkedBlockingQueue<Triple<Long, Long, Boolean>>()

        FakePeer(
            conversation = { peer ->
                val upload = peer.read()
                queued.put(MessageReader(upload.body).readString())

                peer.send(
                    TransferRequest.CODE,
                    MessageWriter()
                        .writeUInt32(TransferRequest.DIRECTION_UPLOAD)
                        .writeUInt32(77)
                        .writeString("Music\\Artist\\track.flac")
                        .writeUInt64(1_234_567)
                        .toByteArray(),
                )

                val reply = peer.read()
                val reader = MessageReader(reply.body)
                response.put(Triple(reply.code, reader.readUInt32(), reader.readBool()))
            },
        ).use { peer ->
            FakeSoulseekServer().use { server ->
                server.peerPort = peer.port

                session(server).use { session ->
                    session.connect()

                    val result = session.requestDownload("some_peer", "Music\\Artist\\track.flac")

                    // What we asked for.
                    assertEquals(
                        "Music\\Artist\\track.flac",
                        queued.poll(5, TimeUnit.SECONDS),
                    )

                    // The outcome we reported to the caller.
                    val accepted = checkNotNull(result as? DownloadRequest.Accepted) {
                        "expected an accepted download, got $result"
                    }
                    assertEquals(77L, accepted.token)
                    assertEquals(1_234_567L, accepted.size)

                    // And what we actually sent back: the peer's token, accepted.
                    val (code, token, allowed) = checkNotNull(response.poll(5, TimeUnit.SECONDS)) {
                        "peer never received a transfer response"
                    }
                    assertEquals(TransferResponse.CODE, code)
                    assertEquals(77L, token)
                    assertTrue("the transfer should have been accepted", allowed)
                }
            }
        }
    }

    @Test(timeout = 30_000)
    fun `download receives the file over a relayed file connection`() {
        val payload = ByteArray(50_000) { (it % 251).toByte() }
        val connections = AtomicInteger()

        FakePeer(
            conversation = { peer ->
                when (connections.incrementAndGet()) {
                    // 1. The direct P connection we ask for the file on.
                    1 -> {
                        peer.read() // QueueUpload
                        peer.send(
                            TransferRequest.CODE,
                            MessageWriter()
                                .writeUInt32(TransferRequest.DIRECTION_UPLOAD)
                                .writeUInt32(TRANSFER_TOKEN)
                                .writeString("track.flac")
                                .writeUInt64(payload.size.toLong())
                                .toByteArray(),
                        )
                        peer.read() // TransferResponse
                    }

                    // 2. The relayed F connection the uploader opens to send the bytes.
                    else -> {
                        peer.outputStream().write(FileTransfer.Init.encode(TRANSFER_TOKEN))
                        peer.outputStream().flush()
                        peer.inputStream().readExactly(FileTransfer.Offset.BYTES) // our offset
                        peer.outputStream().write(payload)
                        peer.outputStream().flush()
                    }
                }
            },
        ).use { peer ->
            FakeSoulseekServer().use { server ->
                server.peerPort = peer.port

                session(server).use { session ->
                    session.connect()

                    val destination = File.createTempFile("sonora-download", ".bin")
                    destination.deleteOnExit()

                    val outcome = LinkedBlockingQueue<DownloadOutcome>()
                    thread(isDaemon = true) {
                        outcome.put(
                            session.download(
                                "some_peer",
                                "track.flac",
                                destination,
                                payload.size.toLong(),
                            ),
                        )
                    }

                    // Once the negotiation is done, the uploader relays a file connection back.
                    assertTrue(peer.awaitConnectionType(PeerInit.TYPE_PEER))
                    server.relay("some_peer", peer.port, token = 1L, type = PeerInit.TYPE_FILE)

                    val result = outcome.poll(20, TimeUnit.SECONDS)
                    assertEquals(DownloadOutcome.Completed(payload.size.toLong()), result)
                    assertArrayEquals(payload, destination.readBytes())
                }
            }
        }
    }

    /**
     * A peer browses us: it connects to the listener the session advertised, asks for the file
     * list, and gets one. This is the reshare read path end to end — the peer only ever sees
     * virtual names, never the real path.
     */
    @Test
    fun `answers a browse request with the shared folder`() {
        val share = folder.newFolder("Soulseek")
        File(share, "a.flac").writeBytes(ByteArray(1200))
        File(share, "b.mp3").writeBytes(ByteArray(340))

        FakeSoulseekServer().use { server ->
            session(server, shareDirectory = share).use { session ->
                session.connect()

                // The session advertises the port its listener actually bound.
                val listenPort = MessageReader(server.await(SetWaitPort.CODE).body).readUInt32().toInt()

                Socket(InetAddress.getLoopbackAddress(), listenPort).use { socket ->
                    socket.soTimeout = 5_000

                    Framing.PEER_INIT.write(
                        socket.getOutputStream(),
                        PeerInit.CODE,
                        MessageWriter()
                            .writeString("browser")
                            .writeString(PeerInit.TYPE_PEER)
                            .writeUInt32(0)
                            .toByteArray(),
                    )

                    Framing.PEER.write(socket.getOutputStream(), SharedFileListRequest.CODE, ByteArray(0))

                    val reply = Framing.PEER.read(socket.getInputStream())
                    assertEquals(SharedFileListResponse.CODE, reply.code)

                    val reader = MessageReader(Zlib.decompress(reply.body))

                    assertEquals(1L, reader.readUInt32())
                    assertEquals("Soulseek", reader.readString())
                    assertEquals(2L, reader.readUInt32())

                    val names = buildList {
                        repeat(2) {
                            assertEquals(1, reader.readByte())
                            add(reader.readString() to reader.readUInt64())
                            reader.readUInt32() // obsolete extension
                            repeat(reader.readUInt32().toInt()) {
                                reader.readUInt32()
                                reader.readUInt32()
                            }
                        }
                    }

                    assertEquals(listOf("a.flac", "b.mp3"), names.map { it.first })
                    assertEquals(listOf(1200L, 340L), names.map { it.second })
                }
            }
        }
    }

    @Test
    fun `a queue upload is answered with an upload transfer request`() {
        val share = folder.newFolder("Soulseek")
        File(share, "song.flac").writeBytes(ByteArray(4321))

        FakeSoulseekServer().use { server ->
            session(server, shareDirectory = share).use { session ->
                session.connect()

                peerTo(server, "downloader").use { socket ->
                    Framing.PEER.write(
                        socket.getOutputStream(),
                        QueueUpload.CODE,
                        QueueUpload.request("Soulseek\\song.flac"),
                    )

                    val reply = Framing.PEER.read(socket.getInputStream())
                    assertEquals(TransferRequest.CODE, reply.code)

                    val request = TransferRequest.parse(reply.body)
                    assertEquals(TransferRequest.DIRECTION_UPLOAD, request.direction)
                    assertEquals("Soulseek\\song.flac", request.filename)
                    assertEquals(4321L, request.size)
                }
            }
        }
    }

    @Test
    fun `a queue upload for something not shared is denied`() {
        val share = folder.newFolder("Soulseek")
        File(share, "song.flac").writeBytes(ByteArray(10))

        FakeSoulseekServer().use { server ->
            session(server, shareDirectory = share).use { session ->
                session.connect()

                peerTo(server, "downloader").use { socket ->
                    Framing.PEER.write(
                        socket.getOutputStream(),
                        QueueUpload.CODE,
                        QueueUpload.request("Soulseek\\nope.flac"),
                    )

                    val reply = Framing.PEER.read(socket.getInputStream())
                    assertEquals(UploadDenied.CODE, reply.code)
                    assertEquals("Soulseek\\nope.flac", UploadDenied.parse(reply.body).filename)
                }
            }
        }
    }

    @Test
    fun `a queue upload cannot escape the share folder`() {
        val share = folder.newFolder("Soulseek")
        File(share, "song.flac").writeBytes(ByteArray(10))

        // Real file, outside the share: reachable only if the requested path were joined onto the
        // share root instead of being matched against what we listed.
        File(folder.root, "outside.flac").writeBytes(ByteArray(10))

        FakeSoulseekServer().use { server ->
            session(server, shareDirectory = share).use { session ->
                session.connect()

                peerTo(server, "downloader").use { socket ->
                    Framing.PEER.write(
                        socket.getOutputStream(),
                        QueueUpload.CODE,
                        QueueUpload.request("Soulseek\\..\\outside.flac"),
                    )

                    assertEquals(UploadDenied.CODE, Framing.PEER.read(socket.getInputStream()).code)
                }
            }
        }
    }

    /** Dials the listener the session advertised and completes a peer handshake on it. */
    private fun peerTo(server: FakeSoulseekServer, username: String): Socket {
        val listenPort = MessageReader(server.await(SetWaitPort.CODE).body).readUInt32().toInt()

        val socket = Socket(InetAddress.getLoopbackAddress(), listenPort)
        socket.soTimeout = 5_000

        Framing.PEER_INIT.write(
            socket.getOutputStream(),
            PeerInit.CODE,
            MessageWriter()
                .writeString(username)
                .writeString(PeerInit.TYPE_PEER)
                .writeUInt32(0)
                .toByteArray(),
        )

        return socket
    }

    private fun session(
        server: FakeSoulseekServer,
        maxConcurrentPeers: Int = SoulseekSession.DEFAULT_MAX_CONCURRENT_PEERS,
        shareDirectory: File? = null,
    ) = SoulseekSession(
        username = "test_user",
        password = "test_password",
        host = "127.0.0.1",
        port = server.port,
        // Ephemeral: the peer listener must not collide with anything on the test machine.
        listenPort = 0,
        maxConcurrentPeers = maxConcurrentPeers,
        shareDirectory = shareDirectory,
    )

    private companion object {
        const val TRANSFER_TOKEN = 4242L
    }
}
