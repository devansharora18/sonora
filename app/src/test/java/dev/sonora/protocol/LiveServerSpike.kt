package dev.sonora.protocol

import dev.sonora.protocol.server.LoginResponse
import dev.sonora.protocol.server.ServerConnection
import dev.sonora.protocol.server.SetStatus
import dev.sonora.protocol.server.SetWaitPort
import dev.sonora.protocol.server.SharedFoldersFiles
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opt-in spikes against the real Soulseek server (milestone 2 in docs/PRD.md §14).
 *
 * Skipped unless credentials are supplied, so they never run in a normal build. Run them
 * with:
 *
 * ```
 * SONORA_SOULSEEK_USER=... SONORA_SOULSEEK_PASS=... \
 *   ./gradlew :app:testDebugUnitTest --tests '*LiveServerSpike*' --info
 * ```
 *
 * `--info` is needed to see the printed output.
 */
class LiveServerSpike {

    @Test
    fun `logs in to the real server`() {
        val (username, password) = LiveServer.credentialsOrSkip()

        LiveServer.connect().use { socket ->
            val connection = ServerConnection(socket)
            val response = LiveServer.login(connection, username, password)

            println("[spike] login response: $response")
            assertTrue("server rejected the login: $response", response is LoginResponse.Success)
        }
    }

    @Test
    fun `session handshake receives server messages`() {
        val (username, password) = LiveServer.credentialsOrSkip()

        LiveServer.connect().use { socket ->
            val connection = ServerConnection(socket)
            LiveServer.login(connection, username, password)

            connection.send(SetWaitPort.CODE, SetWaitPort.request(SetWaitPort.DEFAULT_PORT))
            connection.send(SetStatus.CODE, SetStatus.request(SetStatus.ONLINE))
            connection.send(SharedFoldersFiles.CODE, SharedFoldersFiles.request(0, 0))

            val codes = CopyOnWriteArrayList<Long>()
            connection.startReading { codes += it.code }

            // The server pushes its own messages (room list, speed limits, privileged users)
            // shortly after login. Collect whatever lands in this window.
            Thread.sleep(PUSH_WINDOW_MS)

            println("[spike] server message codes after login: ${codes.toList()}")
            assertTrue("expected the server to push messages after login", codes.isNotEmpty())
        }
    }

    private companion object {
        const val PUSH_WINDOW_MS = 3_000L
    }
}
