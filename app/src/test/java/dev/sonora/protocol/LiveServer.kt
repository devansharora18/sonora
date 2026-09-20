package dev.sonora.protocol

import dev.sonora.protocol.server.Login
import dev.sonora.protocol.server.LoginResponse
import dev.sonora.protocol.server.ServerConnection
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assume.assumeTrue

/**
 * Shared plumbing for the opt-in live spikes.
 *
 * Credentials come from the environment and are never committed. Each spike skips itself
 * when they are absent, so a normal build never touches the network.
 */
internal object LiveServer {

    const val HOST = "server.slsknet.org"
    const val PORT = 2242
    const val CONNECT_TIMEOUT_MS = 15_000

    /** Reserved for experimental development and testing — see docs/protocol-scope.md. */
    const val MAJOR_VERSION = 177
    const val MINOR_VERSION = 1

    const val DEFAULT_QUERY = "aphex twin"

    fun credentialsOrSkip(): Pair<String, String> {
        val username = System.getenv("SONORA_SOULSEEK_USER").orEmpty()
        val password = System.getenv("SONORA_SOULSEEK_PASS").orEmpty()
        assumeTrue(
            "set SONORA_SOULSEEK_USER and SONORA_SOULSEEK_PASS to run this spike",
            username.isNotBlank() && password.isNotBlank(),
        )
        return username to password
    }

    fun query(): String =
        System.getenv("SONORA_SEARCH_QUERY").orEmpty().ifBlank { DEFAULT_QUERY }

    fun connect(): Socket = Socket().apply {
        connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
        soTimeout = CONNECT_TIMEOUT_MS
    }

    /** Sends Login and returns the parsed response. */
    fun login(connection: ServerConnection, username: String, password: String): LoginResponse {
        connection.send(
            Login.CODE,
            Login.request(username, password, MAJOR_VERSION, MINOR_VERSION),
        )
        return Login.parse(connection.read().body)
    }
}
