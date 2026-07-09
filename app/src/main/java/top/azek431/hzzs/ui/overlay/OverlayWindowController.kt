// 火崽崽助手（HZZS）悬浮窗窗口参数控制器。
//
// 职责：
// - 创建 WindowManager.LayoutParams（悬浮窗尺寸、位置、类型、标志位）
// - 提供 dp 转 px 工具方法和边界计算方法
// - 集中管理悬浮窗的基础尺寸常量（BASE_WIDTH_DP = 228dp）
// - 提供最小/最大宽度计算，供缩放控制器参考
//
// 不负责：
// - 不处理拖动逻辑（由 OverlayDragController 处理）
// - 不处理缩放逻辑（由 OverlayResizeController 处理）
// - 不处理滑块绑定（由 OverlaySettingsBinder 处理）
//
// 设计原因：
// - LayoutParams 创建逻辑独立封装，便于将来修改悬浮窗类型/标志位
// - dp 转 px 是通用工具方法，集中管理避免其他地方重复实现
// - 所有尺寸常量集中定义，修改悬浮窗基础宽度只需改一处
//
// 兼容性：
// - API 26+（Oreo）使用 TYPE_APPLICATION_OVERLAY
// - API 23-25 使用 TYPE_PHONE 回退
// - FLAG_NOT_FOCUSABLE 确保悬浮窗不抢占焦点，点击事件穿透到下层应用
// - FLAG_LAYOUT_IN_SCREEN 确保悬浮窗可以定位到屏幕任意位置

package top.azek431.hzzs.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * 悬浮窗窗口参数控制器。
 *
 * 负责创建和管理 WindowManager.LayoutParams，
 * 确保悬浮窗在所有 Android 版本上都能正确显示。
 *
 * @param context 上下文（建议使用 applicationContext）
 */
class OverlayWindowController(private val context: Context) {

    companion object {
        private const val TAG = "HZZS-WindowCtrl"

        /** 悬浮窗基础宽度（dp），对应 view_overlay_preview.xml 中内容面板的期望宽度 */
        private const val BASE_WIDTH_DP = 228

        /** 悬浮窗默认 X 偏移量（dp），从屏幕左边缘向右偏移的距离 */
        private const val DEFAULT_X_DP = 244

        /** 悬浮窗默认 Y 偏移量（dp），从屏幕顶部向下偏移的距离 */
        private const val DEFAULT_Y_DP = 108

        /** 悬浮窗最大宽度倍数（2 倍基础宽度），缩放上限 */
        private const val MAX_WIDTH_MULTIPLIER = 2.0f

        /** 悬浮窗最小宽度倍数（0.5 倍基础宽度），缩放下限 */
        private const val MIN_WIDTH_MULTIPLIER = 0.5f
    }

    /** 应用上下文，用于避免内存泄漏 */
    private val appContext get() = context.applicationContext

    /** 屏幕显示度量信息，用于 dp/px 转换和尺寸计算 */
    private val displayMetrics get() = appContext.resources.displayMetrics

    /**
     * 创建悬浮窗 LayoutParams。
     *
     * 完整参数配置：
     * 1. type：根据 API 版本选择 TYPE_APPLICATION_OVERLAY（API 26+）或 TYPE_PHONE（低版本）
     * 2. flags：FLAG_NOT_FOCUSABLE（不抢占焦点，点击穿透）+ FLAG_LAYOUT_IN_SCREEN（全屏布局）
     * 3. format：PixelFormat.TRANSLUCENT（支持透明度调节）
     * 4. gravity：Gravity.TOP | Gravity.START（从左上角定位）
     * 5. x：屏幕宽度 - DEFAULT_X_DP，使悬浮窗默认靠右显示
     * 6. y：DEFAULT_Y_DP，垂直方向居中偏上
     *
     * @param width 悬浮窗宽度（px），默认基础宽度 BASE_WIDTH_DP 转换而来
     * @param height 悬浮窗高度，默认 WRAP_CONTENT 自适应内容
     * @return 配置好的 WindowManager.LayoutParams
     */
    fun createLayoutParams(
        width: Int = dp(BASE_WIDTH_DP),
        height: Int = WindowManager.LayoutParams.WRAP_CONTENT,
    ): WindowManager.LayoutParams {
        val screenWidth = displayMetrics.widthPixels
        // 计算最大 X 坐标：屏幕宽度 - 悬浮窗宽度，确保悬浮窗不完全移出屏幕右侧
        val maxX = (screenWidth - width).coerceAtLeast(0)

        return WindowManager.LayoutParams(
            width,
            height,
            // API 26+ 使用 TYPE_APPLICATION_OVERLAY，低版本回退到 TYPE_PHONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // FLAG_NOT_FOCUSABLE：悬浮窗不获取焦点，点击事件穿透到下層应用
            // FLAG_LAYOUT_IN_SCREEN：允许悬浮窗布局到整个屏幕区域
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,  // 支持透明度调节
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // x 坐标：屏幕右边缘向内缩进 DEFAULT_X_DP，确保悬浮窗不贴边
            x = (screenWidth - dp(DEFAULT_X_DP)).coerceIn(0, maxX)
            // y 坐标：从屏幕顶部向下 DEFAULT_Y_DP，垂直方向居中偏上
            y = dp(DEFAULT_Y_DP)
        }
    }

    /**
     * 计算悬浮窗最大 X 坐标。
     *
     * 用于拖动时限制悬浮窗不超出屏幕右边界。
     * 计算公式：屏幕宽度 - 悬浮窗当前宽度
     *
     * @param overlayWidth 悬浮窗当前宽度（px）
     * @return 最大 X 坐标（防止悬浮窗完全移出屏幕右侧）
     */
    fun calculateMaxX(overlayWidth: Int): Int {
        val screenWidth = displayMetrics.widthPixels
        return (screenWidth - overlayWidth).coerceAtLeast(0)
    }

    /**
     * 计算悬浮窗最大 Y 坐标。
     *
     * 用于拖动时限制悬浮窗不超出屏幕下边界。
     * 计算公式：屏幕高度 - 悬浮窗当前高度
     *
     * @param overlayHeight 悬浮窗当前高度（px）
     * @return 最大 Y 坐标（防止悬浮窗完全移出屏幕底部）
     */
    fun calculateMaxY(overlayHeight: Int): Int {
        val screenHeight = displayMetrics.heightPixels
        return (screenHeight - overlayHeight).coerceAtLeast(0)
    }

    /**
     * dp 转 px 工具方法。
     *
     * 使用四舍五入确保像素值为整数，避免半像素导致的绘制模糊。
     *
     * @param value dp 值
     * @return 对应的 px 值（四舍五入）
     */
    fun dp(value: Int): Int {
        return (value * displayMetrics.density + 0.5f).toInt()
    }

    /** 获取悬浮窗基础宽度（px），即 BASE_WIDTH_DP 转换后的像素值 */
    fun baseWidthPx(): Int = dp(BASE_WIDTH_DP)

    /** 获取悬浮窗最小宽度（px），即基础宽度的 0.5 倍 */
    fun minWidthPx(): Int = (dp(BASE_WIDTH_DP) * MIN_WIDTH_MULTIPLIER).roundToInt()

    /** 获取悬浮窗最大宽度（px），即基础宽度的 2.0 倍 */
    fun maxWidthPx(): Int = dp(BASE_WIDTH_DP) * MAX_WIDTH_MULTIPLIER.toInt()
}
