package top.azek431.hzzs.runtime.vision

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import top.azek431.hzzs.R
import top.azek431.hzzs.runtime.settings.VisionRuntimeSettingsActivity

class VisionRuntimeService : Service() {
    companion object {
        private const val CHANNEL = "hzzs_vision_runtime"
        private const val NOTIFICATION_ID = 4313
        const val ACTION_START = "top.azek431.hzzs.runtime.START"
        const val ACTION_STOP = "top.azek431.hzzs.runtime.STOP"
        @Volatile private var running = false
        fun isRunning() = running
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, VisionRuntimeService::class.java).setAction(ACTION_START))
        fun stop(context: Context) = context.stopService(Intent(context, VisionRuntimeService::class.java))
    }
    private var controller: VisionRuntimeController? = null
    override fun onCreate() { super.onCreate(); createChannel() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { controller?.close(); controller = null; running = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            else -> {
                val settingsIntent = android.app.PendingIntent.getActivity(this, 0, Intent(this, VisionRuntimeSettingsActivity::class.java), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_hzzs)
                    .setContentTitle("HZZS 视觉分析运行中").setContentText("点击打开视觉、权限与自动操作设置").setContentIntent(settingsIntent).setOngoing(true).build())
                if (controller == null) controller = VisionRuntimeController(this)
                controller?.start(); running = true
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { controller?.close(); controller = null; running = false; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "HZZS 视觉运行", NotificationManager.IMPORTANCE_LOW)) }
}
