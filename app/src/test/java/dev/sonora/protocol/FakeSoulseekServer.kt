package dev.sonora.protocol

import dev.sonora.protocol.peer.PeerInit
import dev.sonora.protocol.server.ConnectToPeer
import dev.sonora.protocol.server.GetPeerAddress
import dev.sonora.protocol.server.Login
import dev.sonora.protocol.server.LoginResponse
import dev.sonora.protocol.server.ServerConnection
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A loopback stand-in for the Soulseek server.
 *
 * Speaks just enough of the server protocol to drive [SoulseekSession] with no network and no
 * credentials, so the session is testable in CI.
 *
 * It encodes its own response bytes rather than reusing production encoders: if the harness
 * shared a writer with the parser under test, a shared mistake would pass silently.
 */
internal class FakeSoulseekServer(
    private val loginResponse: LoginResponse = DEFAULT_RESPONSE,
) : Closeable {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val received = LinkedBlockingQueue<Message>()

    @Volatile
    private var connection: ServerConnection? = null

    @Volatile
    private var client: Socket? = null

    /** The raw Login body the session sent, kept so the test can check the credentials. */
    @Volatile
    var loginBody: ByteArray? = null
        private set

    /**
     * Port reported for every resolved address. Point it at a [FakePeer] so the session's
     * outbound dial lands there.
     */
    @Volatile
    var peerPort: Int = 0

    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "fake-soulseek-server") {
            try {
                val socket = server.accept()
                client = socket

                val conn = ServerConnection(socket)
                connection = conn

                val login = conn.read()
                check(login.code == Login.CODE) { "expected Login, got ${login.code}" }
                loginBody = login.body

                conn.send(Login.CODE, loginBody(loginResponse))
                conn.startReading { message ->
                    received.put(message)

                    // Answer address lookups, since a dial-back needs one to get anywhere.
                    if (message.code == GetPeerAddress.CODE) {
                        val username = MessageReader(message.body).readString()
                        conn.send(GetPeerAddress.CODE, addressBody(username, peerPort))
                    }
                }
            } catch (_: Exception) {
                // Closed before a client connected.
            }
        }
    }

    /** The next message, in arrival order. */
    fun next(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): Message =
        received.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            ?: error("no message arrived within ${timeoutMillis}ms")

    /** The next message with [code], ignoring any others. */
    fun await(code: Long, timeoutMillis: Long = DEFAULT_TIMEOUT_MS): Message {
        val deadline = System.currentTimeMillis() + timeoutMillis

        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) error("timed out waiting for message $code")

            val message = received.poll(remaining, TimeUnit.MILLISECONDS)
                ?: error("timed out waiting for message $code")

            if (message.code == code) return message
        }
    }

    /**
     * Pushes a `ConnectToPeer` relay, as the server does when a peer cannot reach us directly.
     * Every relay points at loopback, so the session's dial-back lands on [FakePeer].
     */
    fun relay(
        username: String,
        peerPort: Int,
        token: Long,
        type: String = PeerInit.TYPE_PEER,
    ) {
        val connection = checkNotNull(connection) { "no client connected yet" }

        connection.send(
            ConnectToPeer.CODE,
            MessageWriter()
                .writeString(username)
                .writeString(type)
                .writeUInt32(LOOPBACK_IP)
                .writeUInt32(peerPort.toLong())
                .writeUInt32(token)
                .writeBool(false)
                .writeUInt32(0) // obfuscation type
                .writeUInt32(0) // obfuscated port
                .toByteArray(),
        )
    }

    override fun close() {
        connection?.close()
        client?.close()
        server.close()
    }

    private fun addressBody(username: String, port: Int): ByteArray = MessageWriter()
        .writeString(username)
        .writeUInt32(LOOPBACK_IP)
        .writeUInt32(port.toLong())
        .writeUInt32(0) // obfuscation type
        .writeUInt16(0) // obfuscated port
        .toByteArray()

    private fun loginBody(response: LoginResponse): ByteArray = when (response) {
        is LoginResponse.Success -> MessageWriter()
            .writeBool(true)
            .writeString(response.greeting)
            .writeUInt32(response.ownIp)
            .writeString(response.passwordHash)
            .writeBool(response.isSupporter)
            .toByteArray()

        is LoginResponse.Rejected -> {
            val writer = MessageWriter().writeBool(false).writeString(response.reason)
            response.detail?.let { writer.writeString(it) }
            writer.toByteArray()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L

        /** Loopback (127.0.0.1) packed the way the wire does it. */
        const val LOOPBACK_IP = 0x7F00_0001L

        val DEFAULT_RESPONSE = LoginResponse.Success(
            greeting = "welcome",
            ownIp = 0x7F00_0001L,
            passwordHash = "5f4dcc3b5aa765d61d8327deb882cf99",
            isSupporter = false,
        )
    }
}
