package top.azek431.hzzs.service.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import top.azek431.hzzs.R
import top.azek431.hzzs.core.logging.AppLog

/**
 * 视觉分析前台服务：仅负责在分析运行期间提升进程优先级，降低 OEM 后台杀进程概率。
 *
 * 职责边界：
 * - **只**提进程优先级；帧循环、截图、手势注入仍由 [top.azek431.hzzs.data.vision.VisionRuntimeController] 在协程里跑。
 * - 不持有任何业务状态；「免责声明版本不足 / 自动操作关闭」等合规门控仍在运行时控制器内。
 * - 进程被杀重启后**不**自动恢复分析（防后台偷跑），仅 alive 期间提优先级。
 *
 * 默认开：每次 [top.azek431.hzzs.data.vision.VisionRuntimeController.start] 同步启前台服务，
 * [top.azek431.hzzs.data.vision.VisionRuntimeController.stop] 同步停。
 */
class VisionAnalysisForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = notification("正在后台分析屏幕，不会上传截图")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun notification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_splash_flame)
            .setContentTitle("火崽崽视觉分析")
            .setContentText(message)
            .setOngoing(true)
            .build()

    companion object {
        const val ACTION_START = "top.azek431.hzzs.vision.START"
        const val ACTION_STOP = "top.azek431.hzzs.vision.STOP"
        const val CHANNEL_ID = "vision_analysis"
        private const val NOTIFICATION_ID = 433

        fun start(context: Context) {
            val intent = Intent(context, VisionAnalysisForegroundService::class.java)
                .setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { error ->
                AppLog.w("vision", "start foreground service failed: ${error.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VisionAnalysisForegroundService::class.java)
                .setAction(ACTION_STOP)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { error ->
                AppLog.w("vision", "stop foreground service failed: ${error.message}")
            }
        }
    }
}

internal fun createVisionAnalysisChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= 26) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(VisionAnalysisForegroundService.CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    VisionAnalysisForegroundService.CHANNEL_ID,
                    "屏幕视觉分析",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
