package dev.sonora.protocol.server

import dev.sonora.protocol.Framing
import dev.sonora.protocol.Message
import java.io.Closeable
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A live connection to the Soulseek server.
 *
 * Read the login response with [read] before calling [startReading]; after that, messages
 * arrive continuously on a background thread and are handed to the callback.
 */
class ServerConnection(
    private val socket: Socket,
    /**
     * Called once, from whichever thread notices first, when the connection ends without us asking
     * it to.
     *
     * A socket that dies mid-session is an ordinary end to a long-lived connection, not a mistake
     * by the caller: the network drops and the server closes idle sessions. Reporting it is what
     * lets the app stop claiming to be connected.
     */
    private val onLost: () -> Unit = {},
) : Closeable {

    @Volatile
    private var closed = false

    /** The reader and a writer can both notice the same death; it is only reported once. */
    private val reported = AtomicBoolean(false)

    private var reader: Thread? = null

    fun send(code: Long, body: ByteArray) {
        try {
            Framing.SERVER.write(socket.getOutputStream(), code, body)
        } catch (_: Exception) {
            // Throwing here would carry a dead socket into whatever happened to be searching at the
            // time; a write to a connection that is already gone is the same event as the reader
            // noticing it, and is handled the same way.
            reportLost()
        }
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
                reportLost()
                return
            }

            try {
                onMessage(message)
            } catch (_: Exception) {
                // The body is untrusted, so one unparseable message must not end the session.
            }
        }
    }

    private fun reportLost() {
        // A connection we closed ourselves has not been lost.
        if (closed) return

        if (reported.compareAndSet(false, true)) onLost()
    }

    override fun close() {
        closed = true
        socket.close()
    }
}
