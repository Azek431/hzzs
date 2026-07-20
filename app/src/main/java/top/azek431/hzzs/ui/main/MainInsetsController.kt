// 火崽崽助手（HZZS）首页系统栏安全区域控制器。
//
// 职责：
// - 处理 Edge-to-Edge 全屏显示后的系统栏安全区域（状态栏 + 导航栏）
// - 为顶部栏和滚动区域自动添加 padding，避免内容被刘海屏/底部横条遮挡
//
// 不负责：
// - 不处理按钮点击事件
// - 不处理对话框显示
// - 不处理悬浮窗权限
//
// 设计原因：
// - 系统栏 Insets 处理与业务逻辑完全无关，单独拆出便于将来修改安全区域策略
// - 如果未来更换 UI 框架（如迁移到 Compose），只需替换此控制器
// - 遵循单一职责原则：UI 布局逻辑与页面业务逻辑分离

package top.azek431.hzzs.ui.main

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 首页系统栏安全区域控制器。
 *
 * 构造函数接收所有需要处理 Insets 的 View 及其初始 padding 值，
 * 在 apply() 调用后自动处理系统栏遮挡问题。
 *
 * @param rootContainer 根容器（通常是 CoordinatorLayout）
 * @param topBarContainer 顶部栏（包含应用名称和副标题）
 * @param homeScrollView 滚动区域（承载首页所有内容）
 * @param topBarPaddingStartInit 顶部栏左侧初始 padding
 * @param topBarPaddingTopInit 顶部栏顶部初始 padding
 * @param topBarPaddingEndInit 顶部栏右侧初始 padding
 * @param topBarPaddingBottomInit 顶部栏底部初始 padding
 * @param scrollPaddingStartInit 滚动区域左侧初始 padding
 * @param scrollPaddingTopInit 滚动区域顶部初始 padding
 * @param scrollPaddingEndInit 滚动区域右侧初始 padding
 * @param scrollPaddingBottomInit 滚动区域底部初始 padding
 */
class MainInsetsController(
    private val rootContainer: View,
    private val topBarContainer: View,
    private val homeScrollView: View,
    private val topBarPaddingStartInit: Int,
    private val topBarPaddingTopInit: Int,
    private val topBarPaddingEndInit: Int,
    private val topBarPaddingBottomInit: Int,
    private val scrollPaddingStartInit: Int,
    private val scrollPaddingTopInit: Int,
    private val scrollPaddingEndInit: Int,
    private val scrollPaddingBottomInit: Int,
) {

    /**
     * 应用系统栏安全区域。
     *
     * 此方法设置一个监听器，当系统栏 insets 发生变化时（如键盘弹出、导航栏显示/隐藏），
     * 自动为顶部栏和滚动区域添加相应的 padding。
     *
     * 处理规则：
     * - 顶部栏：左右两侧 + 顶部添加 safe insets，底部保持初始 padding 不变
     * - 滚动区域：左右两侧 + 底部添加 safe insets，顶部保持初始 padding 不变
     *
     * 为什么不修改顶部栏的底部 padding？
     * 因为顶部栏是固定高度（48dp），不需要底部安全区域。
     * 滚动区域不需要顶部安全区域，因为它的顶部由布局自身控制。
     */
    fun apply() {
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )

            // 顶部栏：左右 + 顶部添加安全区域，底部不变
            topBarContainer.updatePadding(
                left = topBarPaddingStartInit + safeInsets.left,
                top = topBarPaddingTopInit + safeInsets.top,
                right = topBarPaddingEndInit + safeInsets.right,
                bottom = topBarPaddingBottomInit,
            )

            // 滚动区域：左右 + 底部添加安全区域，顶部不变
            homeScrollView.updatePadding(
                left = scrollPaddingStartInit + safeInsets.left,
                top = scrollPaddingTopInit,
                right = scrollPaddingEndInit + safeInsets.right,
                bottom = scrollPaddingBottomInit + safeInsets.bottom,
            )

            insets
        }

        // 触发一次 insets 计算，确保初始状态下 padding 正确
        ViewCompat.requestApplyInsets(rootContainer)
    }
}
