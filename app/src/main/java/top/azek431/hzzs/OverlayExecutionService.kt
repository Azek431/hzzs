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
import android.util.Log

/**
 * Foreground service that manages the floating execution control panel.
 * Creates a draggable overlay window with a close button.
 */
class OverlayExecutionService : Service() {

    companion object {
        private const val TAG = "HzzsOverlayService"
        private const val CHANNEL_ID = "hzzs_overlay_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        private var instance: OverlayExecutionService? = null

        fun getInstance(): OverlayExecutionService? = instance

        fun isInstanceAlive(): Boolean = instance != null
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
        Log.d(TAG, "Service onCreate")
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
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
        Log.d(TAG, "Service onDestroy")
        super.onDestroy()
        instance = null
        hideOverlay()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.overlay_notification_channel_description)
                setShowBadge(false)
            }
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $CHANNEL_ID")
        }
    }

    private fun buildNotification(): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val closeIntent = Intent(this, CloseOverlayReceiver::class.java)
        val closePendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_overlay_notification)
            .setContentIntent(mainPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_close_overlay),
                closePendingIntent,
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    }

    private fun showOverlay() {
        if (overlayView != null) {
            Log.w(TAG, "Overlay already visible, skipping.")
            return
        }

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_execution_overlay, null)
        overlayView = view

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            val displayWidth = windowManager.defaultDisplay.width
            x = displayWidth - dpToPx(280)
            y = dpToPx(60)
        }

        // Close button
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseOverlay)
        btnClose.setOnClickListener {
            Log.d(TAG, "Close button clicked")
            stopAll()
        }

        // Drag handling
        val card = view.findViewById<View>(R.id.overlayCard)
        card.setOnTouchListener { _, event ->
            when (event.actionMasked) {
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
                    try {
                        windowManager.updateViewLayout(card, layoutParams)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Failed to update overlay layout during drag", e)
                    }
                    true
                }
                else -> true
            }
        }

        try {
            windowManager.addView(view, layoutParams)
            Log.d(TAG, "Overlay view added successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Overlay permission was rejected by the system", e)
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Overlay window token is invalid", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Overlay view is already attached", e)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to create overlay", e)
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeView(view)
            Log.d(TAG, "Overlay view removed")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Overlay view was already removed", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to remove overlay view", e)
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
     * Broadcast receiver triggered by the notification close action.
     */
    class CloseOverlayReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "CloseOverlayReceiver triggered")
            val serviceIntent = Intent(context, OverlayExecutionService::class.java)
            context.stopService(serviceIntent)
        }
    }
}
