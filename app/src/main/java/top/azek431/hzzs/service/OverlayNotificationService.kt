package top.azek431.hzzs.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ContextCompat
import androidx.core.app.NotificationCompat
import top.azek431.hzzs.R
import top.azek431.hzzs.ui.overlay.OverlayPreviewManager

class OverlayNotificationService : Service() {
    companion object {
        private const val TAG = "HZZS-NotifSvc"
        private const val CHANNEL_ID = "hzzs_overlay_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "action_start_overlay"
        const val ACTION_STOP = "action_stop_overlay"

        fun start(context: Context) {
            val intent = Intent(context, OverlayNotificationService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayNotificationService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Notification is a real close entry, not merely a foreground-service stop.
                OverlayPreviewManager.hide("notification-action")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
            else -> startForeground(NOTIFICATION_ID, createNotification())
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "[Service] destroyed.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_overlay_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        builder.addAction(
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_close_overlay),
                createStopPendingIntent(),
            ).build(),
        )
        return builder.build()
    }

    private fun createStopPendingIntent(): android.app.PendingIntent {
        val intent = Intent(applicationContext, OverlayNotificationService::class.java).apply {
            action = ACTION_STOP
        }
        val immutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        return android.app.PendingIntent.getService(
            applicationContext,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or immutable,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.overlay_notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
