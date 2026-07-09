// 火崽崽助手（HZZS）悬浮窗 — 视图查找器。
//
// 职责：
// - 从 inflate 后的根 View 中查找所有必需子控件
// - 如果任何必需 View 不存在，抛出 IllegalStateException
//
// 设计原因：
// - 将 10+ 个 findViewById 从 OverlayPreviewManager.show() 中提取出来
// - 避免 show() 方法过长（当前 ~100 行只是视图查找）
// - 失败时在 onCreate 阶段立即暴露，而非运行时 NPE

package top.azek431.hzzs.ui.overlay

import android.content.Context
import android.view.View
import android.widget.TextView
import top.azek431.hzzs.R

/**
 * 悬浮窗视图查找器。
 *
 * 在 OverlayPreviewManager.show() 中 inflate 布局后调用，
 * 一次性查找所有必需子控件，失败时抛出明确的异常信息。
 */
class OverlayViewFinder(
    private val context: Context,
    private val root: View,
) {

    // ==================== 查找结果 ====================

    /** 关闭按钮 */
    val closeButton: View

    /** 拖动区域（标题栏） */
    val dragHandle: View

    /** 内容面板 */
    val contentPanel: View

    /** 状态文本 */
    val statusText: TextView

    /** 状态指示灯 */
    val statusDot: View

    /** 循环执行/停止按钮 */
    val btnCycle: TextView

    /** 单次执行按钮 */
    val btnSingle: TextView

    /** QQ 群链接 */
    val communityQq: View

    /** Telegram 频道链接 */
    val communityTelegram: View

    /** 缩放手柄（可选） */
    val resizeHandle: View?

    /** 根面板 */
    val rootPanel: View

    // ==================== 查找逻辑 ====================

    init {
        closeButton = findRequired(R.id.overlayCloseButton, "overlayCloseButton")
        dragHandle = findRequired(R.id.overlayDragHandle, "overlayDragHandle")
        contentPanel = findRequired(R.id.overlayContentPanel, "overlayContentPanel")
        statusText = findRequired(R.id.overlayStatusText, "overlayStatusText") as TextView
        statusDot = findRequired(R.id.overlayStatusDot, "overlayStatusDot")
        btnCycle = findRequired(R.id.overlayBtnCycle, "overlayBtnCycle") as TextView
        btnSingle = findRequired(R.id.overlayBtnSingle, "overlayBtnSingle") as TextView
        communityQq = findRequired(R.id.overlayCommunityQq, "overlayCommunityQq")
        communityTelegram = findRequired(R.id.overlayCommunityTelegram, "overlayCommunityTelegram")
        resizeHandle = root.findViewById(R.id.overlayResizeHandle)
        rootPanel = findRequired(R.id.overlayRootPanel, "overlayRootPanel")
    }

    /**
     * 查找必需 View，不存在时抛出异常。
     *
     * @param id 资源 ID
     * @param name 调试用名称
     * @return 找到的 View
     */
    private fun findRequired(id: Int, name: String): View {
        return root.findViewById<View>(id)
            ?: throw IllegalStateException("$name is missing from view_overlay_preview.xml")
    }
}
