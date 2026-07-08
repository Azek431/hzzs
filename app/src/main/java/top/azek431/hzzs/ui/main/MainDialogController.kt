// 火崽崽助手（HZZS）首页对话框控制器。
//
// 职责：
// - 显示开发计划弹窗
// - 显示权限说明弹窗（悬浮窗权限引导）
// - 显示普通提示对话框（关闭按钮等）
//
// 不负责：
// - 不处理按钮点击事件（由 MainActionBinder 处理）
// - 不处理悬浮窗权限判断（由 OverlayPermissionController 处理）
// - 不处理社区链接（由 CommunityLinks 处理）
//
// 设计原因：
// - MaterialAlertDialogBuilder 的构建逻辑从 MainActivity 中剥离，使 MainActivity 更薄
// - 对话框显示逻辑与页面业务逻辑分离，便于将来统一对话框样式
// - 所有对话框文本使用字符串资源，通过 stringOrFallback 做降级处理

package top.azek431.hzzs.ui.main

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.azek431.hzzs.R

/**
 * 首页对话框控制器。
 *
 * 所有对话框的显示都通过此控制器进行，确保对话框行为一致、易于维护。
 *
 * @param context 上下文（使用 applicationContext 避免内存泄漏）
 */
class MainDialogController(private val context: Context) {

    companion object {
        /** 默认字符串降级提示 */
        private const val FALLBACK_CLOSE = "关闭"
        private const val FALLBACK_PLAN_TITLE = "开发计划"
        private const val FALLBACK_PLAN_MESSAGE =
            "1. 界面与导航\n2. 权限与设备检查\n3. 跑酷像素分析\n4. 实时 HUD 与本局战报\n5. 历史数据与校准"
    }

    /**
     * 显示开发计划弹窗。
     *
     * 弹窗内容包含开发路线的简要说明，用户点击"关闭"按钮后消失。
     * 此弹窗不阻塞用户操作（非模态）。
     */
    fun showDevelopmentPlan() {
        MaterialAlertDialogBuilder(context)
            .setTitle(stringOrFallback("development_plan_title", FALLBACK_PLAN_TITLE))
            .setMessage(stringOrFallback("development_plan_message", FALLBACK_PLAN_MESSAGE))
            .setPositiveButton(
                stringOrFallback("action_close", FALLBACK_CLOSE),
                null,
            )
            .show()
    }

    /**
     * 显示权限说明弹窗。
     *
     * 用于引导用户理解为什么需要悬浮窗权限，并提供"前往授权"和"关闭"两个选项。
     *
     * @param onGoToSettings 点击"前往授权"时的回调
     * @param onCancel 点击"关闭"时的回调
     */
    fun showOverlayPermissionExplanation(
        onGoToSettings: () -> Unit,
        onCancel: () -> Unit,
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(stringOrFallback("overlay_permission_required_title", "需要悬浮窗权限"))
            .setMessage(stringOrFallback(
                "overlay_permission_required_message",
                "火崽崽助手需要「显示在其他应用上层」的权限，才能显示悬浮窗预览。",
            ))
            .setNegativeButton(
                stringOrFallback("action_close", FALLBACK_CLOSE),
                { _, _ -> onCancel() },
            )
            .setPositiveButton(
                stringOrFallback("overlay_permission_go_to_settings", "去授权"),
                { _, _ -> onGoToSettings() },
            )
            .show()
    }

    /**
     * 获取资源字符串，如果资源不存在则返回提供的默认值。
     *
     * 用于未来功能预留：当新字符串资源尚未加入 strings.xml 时，
     * 不会导致 getString(id) 抛出 NotFoundException，而是优雅降级到 fallback。
     *
     * @param name 字符串资源名称（如 "overlay_preview_open"）
     * @param fallback 资源不存在时的回退文本
     * @return 实际字符串或 fallback
     */
    private fun stringOrFallback(name: String, fallback: String): String {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        return if (id == 0) fallback else context.getString(id)
    }
}
