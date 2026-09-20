package dev.sonora.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import dev.sonora.R
import dev.sonora.backend.SonoraBackend

/**
 * Keeps the process alive while the P2P backend is running.
 *
 * It owns no networking itself: the session lives in [SonoraBackend] in this same process, and
 * this service exists so Android does not reclaim that process while transfers are in progress.
 * See PRD D10 for why the boundary is a direct call rather than a local HTTP API.
 */
class SonoraService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        // Not sticky: the UI owns the lifecycle, so a system restart would give us a service with
        // no session behind it. Recovering from process death is a separate piece of work.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Called when the service is genuinely going away. Uses the session-only path, not the
        // UI's disconnect(), which would call back into stopService.
        SonoraBackend.onServiceDestroyed()
        super.onDestroy()
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_connecting))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    companion object {
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
