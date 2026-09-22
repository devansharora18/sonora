package dev.sonora.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import dev.sonora.MainActivity
import dev.sonora.R
import dev.sonora.backend.BackendState
import dev.sonora.backend.DownloadState
import dev.sonora.backend.SonoraBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while the P2P backend is running.
 *
 * It owns no networking itself: the session lives in [SonoraBackend] in this same process, and
 * this service exists so Android does not reclaim that process while transfers are in progress.
 * See PRD D10 for why the boundary is a direct call rather than a local HTTP API.
 *
 * The notification is the visible cost of that, and it lives as long as the service does — which is
 * the whole time the session is up, not just during a transfer. It therefore reports what the
 * backend is actually doing, and offers a way to end the session, rather than being a banner the
 * user has to reach into the app to be rid of.
 */
class SonoraService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        scope.launch {
            combine(SonoraBackend.state, SonoraBackend.download) { backend, download ->
                textFor(backend, download)
            }.collect { text ->
                // startForeground, not NotificationManager.notify: re-posting through the manager
                // detaches the notification from the service, and the system then leaves it on
                // screen when the service stops.
                startForeground(NOTIFICATION_ID, buildNotification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            // The same path the in-app control takes, so the session is closed properly rather
            // than the service merely being torn down around it.
            SonoraBackend.disconnect(this)
            return START_NOT_STICKY
        }

        // Must happen promptly: the system expects a foreground service to post its notification
        // right away, and the collector above only refines the text afterwards.
        startForeground(NOTIFICATION_ID, buildNotification(textFor(SonoraBackend.state.value, SonoraBackend.download.value)))

        // Not sticky: the UI owns the lifecycle, so a system restart would give us a service with
        // no session behind it. Recovering from process death is a separate piece of work.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()

        // Explicit, because the notification is the service's and the session is over: without it
        // a stopped session can leave the notification behind on screen.
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Called when the service is genuinely going away. Uses the session-only path, not the
        // UI's disconnect(), which would call back into stopService.
        SonoraBackend.onServiceDestroyed()
        super.onDestroy()
    }

    private fun textFor(backend: BackendState, download: DownloadState): String = when {
        download is DownloadState.Downloading ->
            getString(R.string.notification_downloading, download.filename)

        // Surfaced rather than falling through to "connected": a failed transfer is the one thing
        // here the user needs to know about, and they are usually not looking at the app.
        download is DownloadState.Failed ->
            getString(R.string.notification_download_failed, download.filename)

        backend is BackendState.Connecting -> getString(R.string.notification_connecting)

        else -> getString(R.string.notification_connected)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp())
            .addAction(disconnectAction())
            .setOngoing(true)
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun disconnectAction(): Notification.Action {
        val intent = Intent(this, SonoraService::class.java).setAction(ACTION_DISCONNECT)
        val pending = PendingIntent.getService(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Action.Builder(null, getString(R.string.notification_disconnect), pending)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "sonora.backend"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "dev.sonora.action.DISCONNECT"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SonoraService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SonoraService::class.java))
        }
    }
}
