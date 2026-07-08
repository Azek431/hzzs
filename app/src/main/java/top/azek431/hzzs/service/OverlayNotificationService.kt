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
        /** 通知渠道 ID */
        private const val CHANNEL_ID = "hzzs_overlay_channel"
        /** 通知 ID（前台服务必需） */
        private const val NOTIFICATION_ID = 1

        /** 启动前台服务的 Intent action */
        const val ACTION_START = "action_start_overlay"
        /** 停止前台服务的 Intent action */
        const val ACTION_STOP = "action_stop_overlay"

        /**
         * 启动前台服务。
         *
         * 使用 startForegroundService + Intent.action，避免直接 new Intent().setClass()
         * 在跨进程场景下的潜在问题。
         *
         * @param context 上下文
         */
        fun start(context: android.content.Context) {
            context.startForegroundService(
                Intent(context, OverlayNotificationService::class.java).apply {
                    action = ACTION_START
                }
            )
        }

        /**
         * 停止前台服务。
         *
         * 发送 ACTION_STOP intent 触发 stopForeground + stopSelf 流程。
         *
         * @param context 上下文
         */
        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, OverlayNotificationService::class.java))
        }
    }

    // ==================== 生命周期 ====================

    /** 服务创建时初始化通知渠道 */
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "[Service] created.")
        createNotificationChannel()
    }

    /**
     * 处理启动/停止请求。
     *
     * ACTION_START → 启动前台服务（创建通知 + startForeground）
     * ACTION_STOP → 停止前台服务（stopForeground + stopSelf）
     * 其他 → 默认启动前台服务
     *
     * START_NOT_STICKY：系统杀死服务后不自动重启，防止误恢复。
     */
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

    /** 服务销毁时清理资源 */
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "[Service] destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== 通知 ====================

    /**
     * 创建前台通知。
     *
     * 通知包含：
     * - 标题：overlay_notification_title（"悬浮窗运行中"）
     * - 内容：overlay_notification_text（"火崽崽助手正在运行"）
     * - 小图标：ic_overlay_notification
     * - 常驻标记：setOngoing(true)，用户不能滑动清除
     * - 停止按钮：点击后发送 ACTION_STOP intent 停止服务
     */
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

    /**
     * 创建停止服务的 PendingIntent。
     *
     * 用户点击通知中的"停止"按钮时，此 PendingIntent 会发送一个
     * action=ACTION_STOP 的 Intent 到本 Service，触发 stopForeground + stopSelf。
     *
     * FLAG_IMMUTABLE：Android 12+ 要求 PendingIntent 不可变（安全最佳实践）。
     */
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

    /**
     * 创建通知渠道（Android 8.0+ 必需）。
     *
     * 渠道属性：
     * - IMPORTANCE_LOW：不发出声音和震动，不在状态栏显示图标
     * - setShowBadge(false)：不在应用图标上显示角标
     *
     * Android 7.x 及以下不需要通知渠道，直接 return。
     */
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
