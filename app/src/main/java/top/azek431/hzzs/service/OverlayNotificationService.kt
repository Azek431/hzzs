// 火崽崽助手（HZZS）前台通知服务。
//
// 职责：
// 1. 保持悬浮窗不被系统回收（Android 12+ 对后台服务有严格限制）
// 2. 管理 OverlayPreviewManager 的生命周期
// 3. 提供"停止执行"入口（用户可从通知栏关闭）
//
// 与 OverlayPreviewManager 的关系：
// - OverlayPreviewManager 负责悬浮窗的创建/显示/隐藏
// - 此服务负责让应用在前台运行，防止被系统杀死
// - 悬浮窗显示时启动此服务，全部隐藏时停止此服务

package top.azek431.hzzs.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import top.azek431.hzzs.R
import top.azek431.hzzs.ui.overlay.OverlayPreviewManager

class OverlayNotificationService : Service() {

    companion object {
        private const val TAG = "HZZS-NotifSvc"
        private const val CHANNEL_ID = "hzzs_overlay_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "action_start_overlay"
        const val ACTION_STOP = "action_stop_overlay"

        /** 启动前台服务 */
        fun start(context: android.content.Context) {
            context.startForegroundService(
                Intent(context, OverlayNotificationService::class.java).apply {
                    action = ACTION_START
                }
            )
        }

        /** 停止前台服务 */
        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, OverlayNotificationService::class.java))
        }
    }

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "[Service] created.")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                Log.i(TAG, "[Service] started as foreground.")
            }
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
                Log.i(TAG, "[Service] stopped.")
            }
            else -> {
                // 默认启动
                startForeground(NOTIFICATION_ID, createNotification())
            }
        }
        return START_NOT_STICKY  // 系统杀死后不自动重启
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "[Service] destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== 通知 ====================

    /** 创建前台通知 */
    private fun createNotification(): Notification {
        val builder = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_overlay_notification)
            .setOngoing(true)  // 常驻通知，用户不能滑动清除
            .setPriority(Notification.PRIORITY_LOW)

        // 添加"停止"按钮
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.action_close_overlay),
            createStopPendingIntent()
        )

        return builder.build()
    }

    /** 创建停止服务的 PendingIntent */
    private fun createStopPendingIntent(): android.app.PendingIntent {
        val intent = Intent(applicationContext, OverlayNotificationService::class.java).apply {
            action = ACTION_STOP
        }
        return android.app.PendingIntent.getService(
            applicationContext,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 创建通知渠道（Android 8.0+） */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.overlay_notification_channel_description)
            setShowBadge(false)  // 不在图标上显示角标
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
