package dev.sonora.protocol.server

import dev.sonora.protocol.Framing
import dev.sonora.protocol.Message
import java.io.Closeable
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A live connection to the Soulseek server.
 *
 * Read the login response with [read] before calling [startReading]; after that, messages
 * arrive continuously on a background thread and are handed to the callback.
 */
class ServerConnection(private val socket: Socket) : Closeable {

    @Volatile
    private var closed = false

    private var reader: Thread? = null

    fun send(code: Long, body: ByteArray) {
        Framing.SERVER.write(socket.getOutputStream(), code, body)
    }

    /** Reads a single message. Only valid before [startReading]. */
    fun read(): Message = Framing.SERVER.read(socket.getInputStream())

    /** Delivers every subsequent message to [onMessage]. */
    fun startReading(onMessage: (Message) -> Unit) {
        check(reader == null) { "already reading" }

        // A server connection is long-lived and the server can be silent for long stretches,
        // so it must not time out. close() is what unblocks the read.
        socket.soTimeout = 0

        reader = thread(name = "sonora-server-reader", isDaemon = true) { readLoop(onMessage) }
    }

    private fun readLoop(onMessage: (Message) -> Unit) {
        while (!closed) {
            val message = try {
                Framing.SERVER.read(socket.getInputStream())
            } catch (_: Exception) {
                // Socket closed, or framing is desynced — either way the connection is done.
                return
            }

            try {
                onMessage(message)
            } catch (_: Exception) {
                // The body is untrusted, so one unparseable message must not end the session.
            }
        }
    }

    override fun close() {
        closed = true
        socket.close()
    }
}
