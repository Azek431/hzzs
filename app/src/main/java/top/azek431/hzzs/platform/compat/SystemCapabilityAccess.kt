package top.azek431.hzzs.platform.compat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import top.azek431.hzzs.service.automation.HzzsAccessibilityService

/**
 * 系统级能力查询与设置页跳转（悬浮窗 / 无障碍）。
 *
 * 职责：集中 API 边界，避免 feature 散落 `Settings` / Intent 构造。
 * 不变量：只跳转系统设置，不静默开启任何权限；AUTO 截图路径不经此文件升权。
 */
object SystemCapabilityAccess {
    /** 是否已授予「显示在其他应用上层」。 */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 打开本应用的悬浮窗权限页；失败时回退到通用应用详情。
     * 须在 Activity / 带 NEW_TASK 的 Context 上调用。
     */
    fun openOverlayPermissionSettings(context: Context) {
        val packageUri = Uri.parse("package:${context.packageName}")
        val overlayIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            packageUri,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { context.startActivity(overlayIntent) }.isSuccess
        if (!launched) {
            openAppDetailsSettings(context)
        }
    }

    /** 无障碍服务进程是否已连接（比 Secure 设置字符串更准确）。 */
    fun isAccessibilityServiceConnected(): Boolean = HzzsAccessibilityService.isConnected()

    /** 打开系统无障碍设置列表。 */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (!launched) {
            openAppDetailsSettings(context)
        }
    }

    private fun openAppDetailsSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
