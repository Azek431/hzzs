// 火崽崽助手（HZZS）悬浮窗权限控制器。
//
// 职责：
// - 判断当前应用是否具有 SYSTEM_ALERT_WINDOW 悬浮窗权限
// - 打开系统悬浮窗授权页面（Settings.ACTION_MANAGE_OVERLAY_PERMISSION）
// - 权限不足时显示引导提示
//
// 不负责：
// - 不处理悬浮窗的创建/显示/隐藏（由 OverlayPreviewManager 处理）
// - 不处理对话框显示（由 MainDialogController 处理）
// - 不处理按钮文本更新（由 MainActionBinder 处理）
//
// 设计原因：
// - 悬浮窗权限判断逻辑独立封装，便于在不同 Activity 中复用
// - 权限跳转 intent 的构建逻辑与页面业务逻辑分离
// - 如果未来需要增加其他权限检查（如无障碍权限），可以在此类中扩展

package top.azek431.hzzs.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import top.azek431.hzzs.R

/**
 * 悬浮窗权限控制器。
 *
 * 封装所有与 SYSTEM_ALERT_WINDOW 权限相关的操作：
 * 1. 检查权限是否已授予
 * 2. 跳转到系统授权页面
 * 3. 权限跳转失败时显示 Toast 提示
 *
 * @param context 上下文（建议使用 applicationContext）
 */
class OverlayPermissionController(private val context: Context) {

    private val appContext get() = context.applicationContext

    /**
     * 检查当前应用是否具有悬浮窗权限。
     *
     * 判断逻辑：
     * - API < 23（Android 6.0）：不需要悬浮窗权限，直接返回 true
     * - API >= 23：检查 Settings.canDrawOverlays() 是否返回 true
     *
     * @return true 如果已拥有悬浮窗权限
     */
    fun hasPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(appContext)
    }

    /**
     * 打开系统悬浮窗授权页面。
     *
     * 此操作会跳转到系统的"悬浮窗权限管理"页面，用户需要手动授权。
     * 如果系统无法打开此页面（如某些定制 ROM 不支持），会捕获异常并显示 Toast 提示。
     *
     * 注意：此方法不会等待用户授权结果。调用方应在 onResume 中重新检查权限状态。
     */
    fun openSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${appContext.packageName}"),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            appContext.startActivity(intent)
        } catch (e: Exception) {
            // 某些设备可能不支持此 intent action，显示 Toast 提示用户手动授权
            Toast.makeText(
                appContext,
                context.getString(R.string.settings_open_failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
