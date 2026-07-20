// 火崽崽助手（HZZS）悬浮窗设置绑定器。
//
// 职责：
// - 绑定透明度滑块到 UI 组件，实时更新 view.alpha 并持久化
// - 绑定自动操作开关/延迟滑块，控制 AutoOperationService 的行为
// - 从 SharedPreferences 恢复上次保存的参数（透明度/圆角/缩放系数）
// - 将用户调整写入 FeatureFlags（统一管理，避免 SharedPreferences 键不一致）
//
// 不负责：
// - 不处理拖动逻辑（由 OverlayDragController 处理）
// - 不处理缩放逻辑（由 OverlayResizeController 处理）
// - 不处理社区链接绑定（由 CommunityLinks 处理）
// - 不直接读写 SharedPreferences（自动操作设置委托给 FeatureFlags）
//
// 设计原因：
// - 设置绑定逻辑独立封装，便于将来添加更多设置项（如尺寸、圆角等）
// - 自动操作设置统一委托给 FeatureFlags，避免 SharedPreferences 键不一致
// - 使用 lazy 委托延迟初始化 SharedPreferences，避免不必要的 IO
//
// 参数持久化键列表：
// - overlay_alpha：悬浮窗透明度（0.0 ~ 1.0）
// - overlay_radius：圆角半径（dp）
// - overlay_scale_ratio：缩放系数
// - 自动操作相关设置委托给 FeatureFlags（KEY_AUTO_OPERATION_ENABLED / KEY_AUTO_OPERATION_DELAY_MS）

package top.azek431.hzzs.ui.overlay

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import kotlin.math.roundToInt
import top.azek431.hzzs.R
import top.azek431.hzzs.service.AutoActionQueue
import top.azek431.hzzs.util.FeatureFlags

/**
 * 悬浮窗设置绑定器。
 *
 * 负责透明度滑块、自动操作开关/延迟滑块的绑定和参数持久化。
 * 构造函数接收 View 引用，在 bind() 调用后完成所有绑定。
 *
 * 参数恢复流程（restoreAll）：
 * 1. 透明度 → 设置 view.alpha + 滑块进度 + 显示文本
 * 2. 圆角 → 转换为 px 后通过 GradientDrawable 设置背景圆角
 * 3. 缩放系数 → 计算缩放后的宽度并应用到 layoutParams
 *
 * @param context 上下文（建议使用 applicationContext）
 * @param view 悬浮窗根 View（overlayRootPanel），所有设置作用于此 View
 */
class OverlaySettingsBinder(
    private val context: Context,
    private val view: View,
) {

    companion object {
        private const val TAG = "HZZS-SettingsBind"

        /** SharedPreferences 文件名，与 OverlayResizeController.PREFS_NAME 共用 */
        private const val PREFS_NAME = "hzzs_overlay_prefs"

        /** 透明度参数键，值范围 0.0 ~ 1.0 */
        private const val KEY_ALPHA = "overlay_alpha"

        /** 圆角半径参数键，单位 dp，默认 20dp */
        private const val KEY_RADIUS = "overlay_radius"

        /** 缩放系数参数键，与 OverlayResizeController.KEY_SCALE_RATIO 共用 */
        private const val KEY_SCALE_RATIO = "overlay_scale_ratio"
    }

    /** 应用上下文，用于避免内存泄漏 */
    private val appContext get() = context.applicationContext

    /** 屏幕显示度量信息，用于 dp/px 转换 */
    private val displayMetrics get() = appContext.resources.displayMetrics

    /**
     * SharedPreferences 延迟初始化。
     * 使用 by lazy 避免应用启动时立即进行 IO 操作，
     * 仅在首次调用 bind/restore 时才创建文件句柄。
     */
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ==================== UI 控件引用 ====================

    /** 透明度滑块，SeekBar 范围 0~100，映射到 0.0~1.0 的 alpha 值 */
    private var alphaSlider: SeekBar? = view.findViewById(R.id.overlayAlphaSlider)

    /** 透明度显示文本，实时显示当前滑块进度百分比 */
    private var alphaValue: TextView? = view.findViewById(R.id.overlayAlphaValue)

    /** 自动操作开关，SwitchCompat 控件 */
    private var autoOpSwitch: SwitchCompat? = view.findViewById(R.id.overlayAutoOpSwitch)

    /** 自动操作状态文本，显示"已启用"/"已禁用" */
    private var autoOpStatus: TextView? = view.findViewById(R.id.overlayAutoOpStatus)

    /** 自动操作延迟滑块，SeekBar 范围 0~50，映射到 0~500ms */
    private var autoOpDelaySlider: SeekBar? = view.findViewById(R.id.overlayAutoOpDelaySlider)

    /** 自动操作延迟值显示文本，实时显示当前延迟值（如 "100 ms"） */
    private var autoOpDelayValue: TextView? = view.findViewById(R.id.overlayAutoOpDelayValue)

    /**
     * 绑定所有设置控件。
     *
     * 此方法应在 View inflate 完成后调用，
     * 完成透明度滑块、自动操作开关/延迟滑块的绑定和参数恢复。
     *
     * 绑定内容：
     * 1. 透明度滑块：实时调整 view.alpha + 持久化到 SharedPreferences
     * 2. 自动操作开关：切换自动操作的启用/禁用状态（委托给 FeatureFlags）
     * 3. 自动操作延迟滑块：调节操作注入延迟（0~500ms，委托给 FeatureFlags）
     */
    fun bind() {
        bindAlphaSlider()
        bindAutoOpControls()
    }

    /**
     * 绑定透明度滑块。
     *
     * SeekBar 范围 0~100，映射到 0.0~1.0 的 alpha 值，
     * 实时应用到整个悬浮窗 View 的 alpha 属性上。
     * 用户调整后通过 apply() 写入 SharedPreferences。
     *
     * 注意：
     * - fromUser 参数防止程序代码修改进度时触发循环更新
     * - view.alpha 是 View 的整体透明度，影响子控件一并透明
     */
    private fun bindAlphaSlider() {
        alphaSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // 非用户操作（如 restoreAll 设置进度）不触发更新
                if (!fromUser) return

                // 将 0~100 的进度映射到 0.0~1.0 的 alpha 值
                val alpha = progress / 100f
                // 应用到整个悬浮窗 View 的透明度
                view.alpha = alpha
                // 更新百分比显示文本
                alphaValue?.text = "$progress%"
                // 持久化到 SharedPreferences
                prefs.edit().putFloat(KEY_ALPHA, alpha).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /**
     * 绑定自动操作控件。
     *
     * 控件列表：
     * - overlayAutoOpSwitch：SwitchCompat，启用/禁用自动操作
     * - overlayAutoOpStatus：TextView，显示当前状态文本
     * - overlayAutoOpDelaySlider：SeekBar（0~50），映射到 0~500ms 延迟
     * - overlayAutoOpDelayValue：TextView，显示当前延迟值
     *
     * 绑定逻辑：
     * 1. 从 FeatureFlags 恢复开关状态和延迟进度
     * 2. 开关切换时写入 FeatureFlags 并通知 AutoActionQueue
     * 3. 延迟滑块拖动时实时更新显示文本和 FeatureFlags
     */
    private fun bindAutoOpControls() {
        // 从 FeatureFlags 恢复设置（统一来源，避免 SharedPreferences 键不一致）
        val autoOpEnabled = FeatureFlags.isAutoOperationEnabled(appContext)
        val autoOpDelayMs = FeatureFlags.getAutoOperationDelayMs(appContext)
        // 将毫秒数映射到 SeekBar progress（0~50 → 0~500ms）
        val autoOpDelayProgress = (autoOpDelayMs / 10).coerceIn(0, 50)

        // 恢复 UI 状态
        autoOpSwitch?.isChecked = autoOpEnabled
        autoOpDelaySlider?.progress = autoOpDelayProgress
        updateAutoOpStatus(autoOpEnabled)

        // 更新延迟显示文本（progress * 10 = 毫秒数）
        autoOpDelayValue?.text = "${autoOpDelayProgress * 10} ms"

        // 绑定自动操作开关：切换时写入 FeatureFlags 并通知 AutoActionQueue
        autoOpSwitch?.setOnCheckedChangeListener { _, isChecked ->
            FeatureFlags.setAutoOperationEnabled(appContext, isChecked)
            AutoActionQueue.setEnabled(isChecked)
            updateAutoOpStatus(isChecked)
        }

        // 绑定延迟滑块：拖动时实时更新显示文本和 FeatureFlags
        autoOpDelaySlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // progress 范围 0~50，乘以 10 得到毫秒数（0~500ms）
                val ms = progress * 10
                autoOpDelayValue?.text = "$ms ms"
                FeatureFlags.setAutoOperationDelayMs(appContext, ms)
                AutoActionQueue.setDelay(ms)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /**
     * 更新自动操作状态文本。
     *
     * @param enabled 是否启用
     */
    private fun updateAutoOpStatus(enabled: Boolean) {
        val textRes = if (enabled) {
            R.string.overlay_auto_op_status_enabled
        } else {
            R.string.overlay_auto_op_status_disabled
        }
        autoOpStatus?.text = context.getString(textRes)
    }

    /**
     * 恢复所有保存的参数到 UI 组件。
     *
     * 此方法在悬浮窗显示时调用，将用户上次保存的设置应用到当前悬浮窗。
     *
     * 恢复内容：
     * 1. 透明度（alpha）— 直接设置 view.alpha + 滑块进度 + 显示文本
     * 2. 圆角半径（radiusDp）— 转换为 px 后通过 GradientDrawable 设置背景圆角
     * 3. 悬浮窗缩放系数（scaleRatio）— 计算缩放后的宽度并应用到 layoutParams
     *
     * @param baseWidth 基础宽度（px），用于缩放计算
     * @param applyCornerRadius 是否应用圆角（默认 true，可通过 false 跳过）
     */
    fun restoreAll(
        baseWidth: Int,
        applyCornerRadius: Boolean = true,
    ) {
        // 1. 恢复透明度
        val alpha = prefs.getFloat(KEY_ALPHA, 1.0f)
        view.alpha = alpha
        alphaSlider?.progress = (alpha * 100).toInt()
        alphaValue?.text = "${alphaSlider?.progress}%"

        // 2. 恢复圆角半径
        if (applyCornerRadius) {
            val radiusDp = prefs.getInt(KEY_RADIUS, 20)
            val radiusPx = (radiusDp * displayMetrics.density).roundToInt()
            applyOverlayCornerRadius(radiusPx)
        }

        // 3. 恢复缩放系数
        val savedScale = prefs.getFloat(KEY_SCALE_RATIO, 1f)
        if (savedScale != 1f) {
            // 计算缩放后的宽度，限制在 [0.5x, 2.0x] 范围内
            val scaledWidth = (baseWidth * savedScale).toInt().coerceIn(
                (baseWidth * 0.5f).roundToInt(),
                (baseWidth * 2.0f).roundToInt()
            )
            val lp = view.layoutParams
            lp.width = scaledWidth
            view.layoutParams = lp
        }
    }

    /**
     * 将悬浮窗背景的圆角半径设置为指定值。
     *
     * 通过 GradientDrawable.mutate() + setCornerRadii 实现。
     * mutate() 确保不共享 drawable 状态，避免影响其他 View。
     * 如果背景不是 GradientDrawable（如被其他 drawable 替换），则跳过并记录警告。
     *
     * @param radiusPx 圆角半径（像素）
     */
    private fun applyOverlayCornerRadius(radiusPx: Int) {
        val drawable = view.background as? android.graphics.drawable.GradientDrawable
        if (drawable == null) {
            Log.w(TAG, "[Overlay] root background is not a GradientDrawable; corner radius update skipped.")
            return
        }
        drawable.mutate()
        drawable.cornerRadius = radiusPx.toFloat()
    }

    /**
     * 获取当前透明度值。
     *
     * @return 透明度值（0.0 ~ 1.0）
     */
    fun getCurrentAlpha(): Float {
        return prefs.getFloat(KEY_ALPHA, 1.0f)
    }

    /**
     * 获取当前缩放系数。
     *
     * @return 缩放系数（如 1.5 表示 1.5 倍宽度）
     */
    fun getCurrentScaleRatio(): Float {
        return prefs.getFloat(KEY_SCALE_RATIO, 1f)
    }
}
