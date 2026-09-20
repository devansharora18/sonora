package dev.sonora.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import dev.sonora.R
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Hosts the P2P backend for as long as the user wants it running.
 *
 * The HTTP server here is a stand-in for the embedded slskd backend. It exists to prove
 * the UI -> 127.0.0.1 contract on Android before .NET is involved.
 */
class SonoraService : Service() {

    private var server: ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        server?.close()
        server = null
        super.onDestroy()
    }

    private fun startServer() {
        val socket = ServerSocket(PORT, 0, InetAddress.getByName("127.0.0.1"))
        server = socket
        thread(name = "sonora-backend", isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    socket.accept().use(::respond)
                } catch (_: IOException) {
                    break
                }
            }
        }
    }

    private fun respond(socket: Socket) {
        // Drain the request line and headers first. Closing a socket that still has unread
        // data makes the kernel send RST, and the client can lose the response.
        val reader = socket.getInputStream().bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val body = """{"status":"ok","backend":"sonora"}""".toByteArray()
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }

        socket.getOutputStream().apply {
            write(headers.toByteArray())
            write(body)
            flush()
        }
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_connecting))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    companion object {
        const val PORT = 5030

        private const val CHANNEL_ID = "sonora.backend"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SonoraService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SonoraService::class.java))
        }
    }
}
