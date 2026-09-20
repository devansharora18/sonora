package dev.sonora.protocol

import dev.sonora.protocol.server.Login
import dev.sonora.protocol.server.LoginResponse
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in spike (milestone 2, step 1): log in to the real Soulseek server.
 *
 * Skipped unless credentials are supplied, so it never runs as part of a normal build and
 * no credentials live in the repository. Run it with:
 *
 * ```
 * SONORA_SOULSEEK_USER=... SONORA_SOULSEEK_PASS=... \
 *   ./gradlew :app:testDebugUnitTest --tests '*LiveLoginSpike*' --info
 * ```
 *
 * `--info` is needed to see the printed response.
 *
 * Major version 177 is the value reserved for experimental development and testing
 * (docs/protocol-scope.md), so this does not impersonate an established client.
 */
class LiveLoginSpike {

    @Test
    fun `logs in to the real Soulseek server`() {
        val username = System.getenv("SONORA_SOULSEEK_USER").orEmpty()
        val password = System.getenv("SONORA_SOULSEEK_PASS").orEmpty()
        assumeTrue(
            "set SONORA_SOULSEEK_USER and SONORA_SOULSEEK_PASS to run this spike",
            username.isNotBlank() && password.isNotBlank(),
        )

        Socket().use { socket ->
            socket.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS

            Framing.SERVER.write(
                socket.getOutputStream(),
                Login.CODE,
                Login.request(username, password, MAJOR_VERSION, MINOR_VERSION),
            )

            val response = Login.parse(Framing.SERVER.read(socket.getInputStream()).body)

            println("[spike] login response: $response")
            assertTrue("server rejected the login: $response", response is LoginResponse.Success)
        }
    }

    private companion object {
        const val HOST = "server.slsknet.org"
        const val PORT = 2242
        const val TIMEOUT_MS = 15_000
        const val MAJOR_VERSION = 177
        const val MINOR_VERSION = 1
    }
}
