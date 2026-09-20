package dev.sonora.protocol

import dev.sonora.protocol.peer.FileSearchResponse
import dev.sonora.protocol.peer.PierceFireWall
import dev.sonora.protocol.peer.SearchResponse
import dev.sonora.protocol.peer.SharedFile
import dev.sonora.protocol.peer.PeerListener
import dev.sonora.protocol.server.ConnectToPeer
import dev.sonora.protocol.server.FileSearch
import dev.sonora.protocol.server.LoginResponse
import dev.sonora.protocol.server.PeerAddress
import dev.sonora.protocol.server.ServerConnection
import dev.sonora.protocol.server.SetStatus
import dev.sonora.protocol.server.SetWaitPort
import dev.sonora.protocol.server.SharedFoldersFiles
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The end-to-end spike: search the real network and parse a real result.
 *
 * Exercises the whole chain — login, session messages, `FileSearch`, `ConnectToPeer`, the
 * outbound `PierceFireWall` fallback, and a zlib-compressed `FileSearchResponse`.
 *
 * Because this host has no inbound reachability (PRD D11), the **fallback** is the path that
 * matters here, and it is also the path every mobile user will take.
 *
 * Run with:
 * ```
 * SONORA_SOULSEEK_USER=... SONORA_SOULSEEK_PASS=... \
 *   ./gradlew :app:testDebugUnitTest --tests '*LiveSearchSpike*' --info
 * ```
 *
 * Override the query with `SONORA_SEARCH_QUERY`.
 */
class LiveSearchSpike {

    @Test
    fun `searching the network returns results`() {
        val (username, password) = LiveServer.credentialsOrSkip()
        val query = LiveServer.query()

        val responses = LinkedBlockingQueue<SearchResponse>()
        val serverCodes = CopyOnWriteArrayList<Long>()
        val connectRequests = AtomicInteger()
        val dialFailures = AtomicInteger()

        PeerListener(port = LISTEN_PORT) { session ->
            println("[spike] inbound peer connection from ${session.username}")
        }.use { listener ->
            LiveServer.connect().use { socket ->
                val connection = ServerConnection(socket)

                val login = LiveServer.login(connection, username, password)
                println("[spike] login: $login")
                assertTrue("login rejected: $login", login is LoginResponse.Success)

                connection.send(SetWaitPort.CODE, SetWaitPort.request(listener.boundPort))
                connection.send(SetStatus.CODE, SetStatus.request(SetStatus.ONLINE))
                connection.send(SharedFoldersFiles.CODE, SharedFoldersFiles.request(0, 0))

                connection.startReading { message ->
                    serverCodes += message.code

                    if (message.code == ConnectToPeer.CODE) {
                        connectRequests.incrementAndGet()
                        val address = ConnectToPeer.parse(message.body)
                        println(
                            "[spike] connect request: ${address.username} " +
                                "${address.ipAddress()}:${address.port} (${address.connectionType})",
                        )
                        dial(address, responses, dialFailures)
                    }
                }

                connection.send(FileSearch.CODE, FileSearch.request(TOKEN, query))
                println("[spike] search sent: query=\"$query\" token=$TOKEN")

                val first = responses.poll(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                // Printed before asserting, so a failure says *which* stage failed rather than
                // just "no results".
                println(
                    "[spike] diagnostics: serverMessages=${serverCodes.size} " +
                        "connectRequests=${connectRequests.get()} " +
                        "dialFailures=${dialFailures.get()} responses=${responses.size}",
                )
                println("[spike] server message codes: ${serverCodes.toList()}")

                if (first == null) {
                    fail("no search response arrived — read the diagnostics above to tell a " +
                        "network problem from a code problem")
                }

                val collected = mutableListOf(first as SearchResponse)
                responses.drainTo(collected)
                val files: List<SharedFile> = collected.flatMap { it.files }

                println("[spike] ${collected.size} response(s), ${files.size} file(s)")
                files.take(5).forEach {
                    println("[spike]   ${it.filename}  ${it.size} bytes  ${it.attributes}")
                }

                assertNotNull(collected)
                assertTrue("responses arrived but carried no files", files.isNotEmpty())
            }
        }
    }

    /**
     * Dials a peer that asked for an indirect connection and completes the handshake. Runs on
     * its own thread so the server read loop keeps draining `ConnectToPeer` messages.
     */
    private fun dial(
        address: PeerAddress,
        responses: BlockingQueue<SearchResponse>,
        failures: AtomicInteger,
    ) {
        thread(name = "sonora-spike-dial", isDaemon = true) {
            try {
                Socket().use { peer ->
                    peer.connect(
                        InetSocketAddress(address.ipAddress(), address.port.toInt()),
                        DIAL_TIMEOUT_MS,
                    )
                    peer.soTimeout = PEER_READ_TIMEOUT_MS

                    Framing.PEER_INIT.write(
                        peer.getOutputStream(),
                        PierceFireWall.CODE,
                        PierceFireWall.request(address.token),
                    )
                    println("[spike] handshake sent to ${address.username}")

                    while (true) {
                        val message = try {
                            Framing.PEER.read(peer.getInputStream())
                        } catch (_: SocketTimeoutException) {
                            break // peer has finished sending
                        }

                        println("[spike] peer message ${message.code} from ${address.username}")
                        if (message.code == FileSearchResponse.CODE) {
                            responses += FileSearchResponse.parse(message.body)
                        }
                    }
                }
            } catch (e: Exception) {
                failures.incrementAndGet()
                println(
                    "[spike] dial failed for ${address.username}: " +
                        "${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }
    }

    private companion object {
        const val LISTEN_PORT = SetWaitPort.DEFAULT_PORT
        const val TOKEN = 0x5EEDL
        const val SEARCH_TIMEOUT_SECONDS = 60L
        const val DIAL_TIMEOUT_MS = 10_000
        const val PEER_READ_TIMEOUT_MS = 20_000
    }
}
