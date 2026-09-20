package dev.sonora.protocol.peer

import dev.sonora.protocol.readExactly
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Uses a real loopback socket pair, so the byte plumbing is exercised for real rather than
 * simulated with in-memory streams.
 */
class FileTransferTest {

    @Test(timeout = 15_000)
    fun `receives a file announced by its token`() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }

        withPeerPair { receiver, uploader ->
            val announcedToken = LinkedBlockingQueue<Long>()
            val receivedOffset = LinkedBlockingQueue<Long>()

            thread(isDaemon = true) {
                uploader.getOutputStream().apply {
                    write(FileTransfer.Init.encode(TRANSFER_TOKEN))
                    flush()
                }

                receivedOffset.put(
                    FileTransfer.Offset.parse(uploader.getInputStream().readExactly(8)),
                )
                announcedToken.put(TRANSFER_TOKEN)

                uploader.getOutputStream().apply {
                    write(payload)
                    flush()
                }
            }

            val token = FileTransfer.readInitToken(receiver.getInputStream())
            FileTransfer.requestFrom(receiver.getOutputStream(), offset = 0)

            val destination = ByteArrayOutputStream()
            val copied = FileTransfer.copyBytes(
                receiver.getInputStream(),
                destination,
                payload.size.toLong(),
            )

            assertEquals(TRANSFER_TOKEN, token)
            assertEquals(TRANSFER_TOKEN, announcedToken.poll(5, TimeUnit.SECONDS))
            assertEquals("a fresh download should resume from zero", 0L, receivedOffset.poll(5, TimeUnit.SECONDS))
            assertEquals(payload.size.toLong(), copied)
            assertArrayEquals(payload, destination.toByteArray())
        }
    }

    @Test(timeout = 15_000)
    fun `requests a resume from a non-zero offset`() {
        withPeerPair { receiver, uploader ->
            val receivedOffset = LinkedBlockingQueue<Long>()

            thread(isDaemon = true) {
                uploader.getOutputStream().write(FileTransfer.Init.encode(7))
                uploader.getOutputStream().flush()
                receivedOffset.put(
                    FileTransfer.Offset.parse(uploader.getInputStream().readExactly(8)),
                )
            }

            FileTransfer.readInitToken(receiver.getInputStream())
            FileTransfer.requestFrom(receiver.getOutputStream(), offset = 4096)

            assertEquals(4096L, receivedOffset.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 15_000)
    fun `reports a short count when the uploader hangs up early`() {
        val promised = 1_000L
        val sent = ByteArray(400) { 0x5A }

        withPeerPair { receiver, uploader ->
            thread(isDaemon = true) {
                uploader.getOutputStream().write(FileTransfer.Init.encode(1))
                uploader.getOutputStream().flush()
                uploader.getInputStream().readExactly(8) // consume the offset
                uploader.getOutputStream().write(sent)
                uploader.getOutputStream().flush()
                uploader.close()
            }

            FileTransfer.readInitToken(receiver.getInputStream())
            FileTransfer.requestFrom(receiver.getOutputStream(), offset = 0)

            val destination = ByteArrayOutputStream()
            val copied = FileTransfer.copyBytes(receiver.getInputStream(), destination, promised)

            assertEquals(sent.size.toLong(), copied)
            assertArrayEquals(sent, destination.toByteArray())
        }
    }

    @Test(timeout = 15_000)
    fun `init and offset round-trip`() {
        assertEquals(0xDEAD_BEEFL, FileTransfer.Init.parse(FileTransfer.Init.encode(0xDEAD_BEEFL)))
        assertEquals(4, FileTransfer.Init.encode(1).size)
        assertEquals(8, FileTransfer.Offset.encode(1).size)
    }

    private fun withPeerPair(block: (Socket, Socket) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { receiver ->
                server.accept().use { uploader ->
                    block(receiver, uploader)
                }
            }
        }
    }

    private companion object {
        const val TRANSFER_TOKEN = 4242L
    }
}
