package dev.sonora.protocol

import dev.sonora.protocol.server.Login
import dev.sonora.protocol.server.LoginResponse
import dev.sonora.protocol.server.SetStatus
import dev.sonora.protocol.server.SetWaitPort
import dev.sonora.protocol.server.SharedFoldersFiles
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in spikes against the real Soulseek server (milestone 2 in docs/PRD.md §14).
 *
 * Skipped unless credentials are supplied, so they never run in a normal build and no
 * credentials live in the repository. Run them with:
 *
 * ```
 * SONORA_SOULSEEK_USER=... SONORA_SOULSEEK_PASS=... \
 *   ./gradlew :app:testDebugUnitTest --tests '*LiveServerSpike*' --info
 * ```
 *
 * `--info` is needed to see the printed output.
 *
 * Major version 177 is the value reserved for experimental development and testing
 * (docs/protocol-scope.md), so this does not impersonate an established client.
 */
class LiveServerSpike {

    @Test
    fun `logs in to the real server`() {
        val (username, password) = credentialsOrSkip()

        connect().use { socket ->
            val response = login(socket, username, password)

            println("[spike] login response: $response")
            assertTrue("server rejected the login: $response", response is LoginResponse.Success)
        }
    }

    @Test
    fun `session handshake receives server messages`() {
        val (username, password) = credentialsOrSkip()

        connect().use { socket ->
            login(socket, username, password)

            val out = socket.getOutputStream()
            Framing.SERVER.write(out, SetWaitPort.CODE, SetWaitPort.request(LISTEN_PORT))
            Framing.SERVER.write(out, SetStatus.CODE, SetStatus.request(SetStatus.ONLINE))
            Framing.SERVER.write(out, SharedFoldersFiles.CODE, SharedFoldersFiles.request(0, 0))

            // After login the server pushes several messages of its own (room list,
            // privileged users, parent speed limits). Reading them proves sequential frames
            // parse off a single stream, which the single-message login test cannot show.
            socket.soTimeout = QUIET_TIMEOUT_MS
            val codes = mutableListOf<Long>()
            try {
                while (codes.size < MAX_MESSAGES) {
                    codes += Framing.SERVER.read(socket.getInputStream()).code
                }
            } catch (_: SocketTimeoutException) {
                // Nothing more pending.
            }

            println("[spike] server message codes after login: $codes")
            assertTrue("expected the server to push messages after login", codes.isNotEmpty())
        }
    }

    private fun credentialsOrSkip(): Pair<String, String> {
        val username = System.getenv("SONORA_SOULSEEK_USER").orEmpty()
        val password = System.getenv("SONORA_SOULSEEK_PASS").orEmpty()
        assumeTrue(
            "set SONORA_SOULSEEK_USER and SONORA_SOULSEEK_PASS to run this spike",
            username.isNotBlank() && password.isNotBlank(),
        )
        return username to password
    }

    private fun connect(): Socket = Socket().apply {
        connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
        soTimeout = TIMEOUT_MS
    }

    private fun login(socket: Socket, username: String, password: String): LoginResponse {
        Framing.SERVER.write(
            socket.getOutputStream(),
            Login.CODE,
            Login.request(username, password, MAJOR_VERSION, MINOR_VERSION),
        )
        return Login.parse(Framing.SERVER.read(socket.getInputStream()).body)
    }

    private companion object {
        const val HOST = "server.slsknet.org"
        const val PORT = 2242
        const val TIMEOUT_MS = 15_000
        const val QUIET_TIMEOUT_MS = 2_000
        const val MAX_MESSAGES = 50
        const val MAJOR_VERSION = 177
        const val MINOR_VERSION = 1

        /**
         * We do not accept peer connections yet, so this is a placeholder. It becomes real
         * once the peer listener exists.
         */
        const val LISTEN_PORT = SetWaitPort.DEFAULT_PORT
    }
}
