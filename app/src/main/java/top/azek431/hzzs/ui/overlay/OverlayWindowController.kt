// 火崽崽助手（HZZS）悬浮窗窗口参数控制器。
//
// 职责：
// - 创建 WindowManager.LayoutParams（悬浮窗尺寸、位置、类型、标志位）
// - 提供 dp 转 px 工具方法
//
// 不负责：
// - 不处理拖动逻辑（由 OverlayDragController 处理）
// - 不处理缩放逻辑（由 OverlayResizeController 处理）
// - 不处理滑块绑定（由 OverlaySettingsBinder 处理）
//
// 设计原因：
// - LayoutParams 创建逻辑独立封装，便于将来修改悬浮窗类型/标志位
// - dp 转 px 是通用工具方法，集中管理避免其他地方重复实现

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

        /** 悬浮窗基础宽度（dp） */
        private const val BASE_WIDTH_DP = 228

        /** 悬浮窗默认 X 偏移量（dp） */
        private const val DEFAULT_X_DP = 244

        /** 悬浮窗默认 Y 偏移量（dp） */
        private const val DEFAULT_Y_DP = 108

        /** 悬浮窗最大宽度倍数（2 倍基础宽度） */
        private const val MAX_WIDTH_MULTIPLIER = 2.0f

        /** 悬浮窗最小宽度倍数（0.5 倍基础宽度） */
        private const val MIN_WIDTH_MULTIPLIER = 0.5f
    }

    private val appContext get() = context.applicationContext
    private val displayMetrics get() = appContext.resources.displayMetrics

    /**
     * 创建悬浮窗 LayoutParams。
     *
     * 参数说明：
     * - type：API 26+ 使用 TYPE_APPLICATION_OVERLAY，低版本回退到 TYPE_PHONE
     * - flags：FLAG_NOT_FOCUSABLE（不抢占焦点）+ FLAG_LAYOUT_IN_SCREEN（全屏布局）
     * - format：TRANSLUCENT（支持透明度调节）
     * - gravity：TOP | START（从左上角定位）
     * - x/y：默认屏幕右侧留 244dp，垂直居中偏上 108dp
     *
     * @param width 悬浮窗宽度（px），默认基础宽度
     * @param height 悬浮窗高度（WRAP_CONTENT 使用 -2）
     * @return 配置好的 WindowManager.LayoutParams
     */
    fun createLayoutParams(
        width: Int = dp(BASE_WIDTH_DP),
        height: Int = WindowManager.LayoutParams.WRAP_CONTENT,
    ): WindowManager.LayoutParams {
        val screenWidth = displayMetrics.widthPixels
        val maxX = (screenWidth - width).coerceAtLeast(0)

        return WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - dp(DEFAULT_X_DP)).coerceIn(0, maxX)
            y = dp(DEFAULT_Y_DP)
        }
    }

    /**
     * 计算悬浮窗最大 X 坐标。
     *
     * @param overlayWidth 悬浮窗宽度（px）
     * @return 最大 X 坐标（防止悬浮窗完全移出屏幕右侧）
     */
    fun calculateMaxX(overlayWidth: Int): Int {
        val screenWidth = displayMetrics.widthPixels
        return (screenWidth - overlayWidth).coerceAtLeast(0)
    }

    /**
     * 计算悬浮窗最大 Y 坐标。
     *
     * @param overlayHeight 悬浮窗高度（px）
     * @return 最大 Y 坐标（防止悬浮窗完全移出屏幕底部）
     */
    fun calculateMaxY(overlayHeight: Int): Int {
        val screenHeight = displayMetrics.heightPixels
        return (screenHeight - overlayHeight).coerceAtLeast(0)
    }

    /**
     * dp 转 px 工具方法。
     *
     * @param value dp 值
     * @return 对应的 px 值（四舍五入）
     */
    fun dp(value: Int): Int {
        return (value * displayMetrics.density + 0.5f).toInt()
    }

    /**
     * 获取悬浮窗基础宽度（px）。
     */
    fun baseWidthPx(): Int = dp(BASE_WIDTH_DP)

    /**
     * 获取悬浮窗最小宽度（px）。
     */
    fun minWidthPx(): Int = dp(BASE_WIDTH_DP) * MIN_WIDTH_MULTIPLIER.toInt()

    /**
     * 获取悬浮窗最大宽度（px）。
     */
    fun maxWidthPx(): Int = dp(BASE_WIDTH_DP) * MAX_WIDTH_MULTIPLIER.toInt()
}
