// 火崽崽助手（HZZS）首页按钮点击事件绑定器。
//
// 职责：
// - 绑定首页所有按钮的点击事件（开发计划、悬浮窗开关、免责声明）
// - 通过回调接口将业务逻辑交给调用方处理
//
// 不负责：
// - 不处理悬浮窗权限检查（由 OverlayPermissionController 处理）
// - 不处理对话框显示（由 MainDialogController 处理）
// - 不处理社区链接（由 CommunityLinks 处理）
//
// 设计原因：
// - 将setOnClickListener 的逻辑从 MainActivity 中剥离，使 MainActivity 更薄
// - 使用回调接口（MainActionCallbacks）解耦 UI 与业务逻辑
// - 按钮绑定是纯 UI 行为，与业务逻辑分离便于测试和维护

package top.azek431.hzzs.ui.main

import com.google.android.material.button.MaterialButton
import top.azek431.hzzs.R

/**
 * 首页按钮点击事件的回调接口。
 *
 * 由 MainActivity 实现，接收按钮点击后产生的业务事件。
 * 这样 MainActionBinder 不需要知道任何业务细节，只负责"谁点了哪个按钮"。
 */
interface MainActionCallbacks {
    /** 点击了"查看开发计划"按钮 */
    fun onDevelopmentPlanClicked()

    /** 点击了"悬浮窗开关"按钮 */
    fun onOverlayToggleClicked()

    /** 点击了"免责声明"按钮 */
    fun onDisclaimerClicked()
}

/**
 * 首页按钮点击事件绑定器。
 *
 * 构造函数接收所有需要绑定的 View 和回调接口，
 * 在 bind() 调用后完成所有按钮的点击事件注册。
 *
 * @param btnDevelopmentPlan 开发计划按钮
 * @param btnOverlayExecution 悬浮窗开关按钮
 * @param btnDisclaimer 免责声明按钮
 * @param callbacks 业务回调接口
 */
class MainActionBinder(
    private val btnDevelopmentPlan: MaterialButton,
    private val btnOverlayExecution: MaterialButton,
    private val btnDisclaimer: MaterialButton,
    private val callbacks: MainActionCallbacks,
) {

    /**
     * 绑定所有按钮的点击事件。
     *
     * 此方法应在 Activity 的 onCreate 中调用，
     * 在 View 缓存完成后执行。
     */
    fun bind() {
        btnDevelopmentPlan.setOnClickListener {
            callbacks.onDevelopmentPlanClicked()
        }

        btnOverlayExecution.setOnClickListener {
            callbacks.onOverlayToggleClicked()
        }

        btnDisclaimer.setOnClickListener {
            callbacks.onDisclaimerClicked()
        }
    }

    /**
     * 更新悬浮窗按钮文本。
     *
     * 根据悬浮窗当前显示状态切换按钮文字：
     * - 显示中 → "关闭悬浮窗"
     * - 未显示 → "打开悬浮窗"
     *
     * @param isShowing 悬浮窗是否正在显示
     */
    fun updateOverlayButtonText(isShowing: Boolean) {
        btnOverlayExecution.text = if (isShowing) {
            btnOverlayExecution.context.getString(R.string.overlay_preview_close)
        } else {
            btnOverlayExecution.context.getString(R.string.overlay_preview_open)
        }
    }
}
