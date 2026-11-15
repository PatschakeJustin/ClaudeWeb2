package app.devdeck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.devdeck.ui.MainActivity

/**
 * Foreground service that keeps the app process – and with it the SSH terminal – alive while the user
 * switches to other apps (e.g. the browser for a login). Shows a notification with a "Trennen" action.
 */
class TerminalService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            (application as DevDeckApp).container.terminal.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "Server"
        val notification = buildNotification(name)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(name: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Terminal-Verbindung", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Hält die SSH-Verbindung offen, während du andere Apps nutzt."
            })
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, TerminalService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Terminal verbunden: $name")
            .setContentText("Die Verbindung bleibt offen, auch wenn du die App verlässt.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Trennen", disconnect)
            .build()
    }

    companion object {
        private const val CHANNEL = "terminal"
        private const val ID = 42
        private const val EXTRA_NAME = "name"
        private const val ACTION_DISCONNECT = "app.devdeck.DISCONNECT"

        fun start(context: Context, serverName: String) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, TerminalService::class.java).putExtra(EXTRA_NAME, serverName),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TerminalService::class.java)) }
        }
    }
}
