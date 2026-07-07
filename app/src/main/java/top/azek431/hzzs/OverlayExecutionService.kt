package top.azek431.hzzs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.core.app.NotificationCompat

/**
 * Foreground service that manages the floating execution control panel.
 * Creates a draggable overlay window with a close button.
 */
class OverlayExecutionService : Service() {

    companion object {
        private const val CHANNEL_ID = "hzzs_overlay_channel"
        private const val NOTIFICATION_ID = 1001

        // Singleton reference for duplicate prevention
        @Volatile
        private var instance: OverlayExecutionService? = null

        /**
         * Check if an overlay instance is currently alive.
         */
        fun isInstanceAlive(): Boolean {
            return instance != null
        }
    }

    private val binder = LocalBinder()
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val handler = Handler(Looper.getMainLooper())
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var initialLayoutX = 0
    private var initialLayoutY = 0

    inner class LocalBinder : Binder() {
        fun getService(): OverlayExecutionService = this@OverlayExecutionService
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        showOverlay()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        hideOverlay()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.overlay_notification_channel_description)
                setShowBadge(false)
            }
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // PendingIntent to bring MainActivity to foreground
        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Close action: broadcast to stop service
        val closeIntent = Intent(this, CloseOverlayReceiver::class.java)
        val closePendingIntent = PendingIntent.getBroadcast(
            this, 0, closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_overlay_notification)
            .setContentIntent(mainPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_close_overlay),
                closePendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showOverlay() {
        if (overlayView != null) {
            return
        }

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_execution_overlay, null)
        overlayView = view

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            // Position: top right, avoiding status bar area
            // Status bar height is typically ~24dp, so offset by ~36px to be safe
            x = windowManager.defaultDisplay.width - dpToPx(280)
            y = dpToPx(60)
            token = null
        }

        // Close button
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseOverlay)
        btnClose.setOnClickListener {
            stopAll()
        }

        // Drag handling
        val card = view.findViewById<View>(R.id.overlayCard)
        card.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    initialLayoutX = layoutParams.x
                    initialLayoutY = layoutParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastTouchX
                    val dy = event.rawY - lastTouchY
                    layoutParams.x = initialLayoutX + dx.toInt()
                    layoutParams.y = initialLayoutY + dy.toInt()
                    windowManager.updateViewLayout(card, layoutParams)
                    true
                }
                else -> true
            }
        }

        windowManager.addView(view, layoutParams)
    }

    private fun hideOverlay() {
        if (::windowManager.isInitialized && overlayView != null) {
            try {
                windowManager.removeView(overlayView)
            } catch (_: IllegalArgumentException) {
                // View was already removed
            }
            overlayView = null
        }
    }

    private fun stopAll() {
        handler.post {
            hideOverlay()
            stopForeground(true)
            stopSelf()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }

    /**
     * Broadcast receiver for the notification close action.
     */
    class CloseOverlayReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val serviceIntent = Intent(context, OverlayExecutionService::class.java)
            context.stopService(serviceIntent)
        }
    }
}
