// 火崽崽助手（HZZS）悬浮窗设置绑定器。
//
// 职责：
// - 绑定透明度滑块到 UI 组件
// - 从 SharedPreferences 恢复上次保存的参数（透明度/圆角/缩放系数）
// - 将用户调整写入 SharedPreferences
//
// 不负责：
// - 不处理拖动逻辑（由 OverlayDragController 处理）
// - 不处理缩放逻辑（由 OverlayResizeController 处理）
// - 不处理社区链接绑定（由 CommunityLinks 处理）
//
// 设计原因：
// - 设置绑定逻辑独立封装，便于将来添加更多设置项（如尺寸、圆角等）
// - SharedPreferences 读写集中在一个类中，避免多处散乱访问

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

/**
 * 悬浮窗设置绑定器。
 *
 * 负责透明度滑块、自动操作开关/延迟滑块的绑定和参数持久化。
 * 构造函数接收 View 引用，在 bind() 调用后完成所有绑定。
 *
 * @param context 上下文（建议使用 applicationContext）
 * @param view 悬浮窗根 View
 */
class OverlaySettingsBinder(
    private val context: Context,
    private val view: View,
) {

    companion object {
        private const val TAG = "HZZS-SettingsBind"

        /** SharedPreferences 文件名 */
        private const val PREFS_NAME = "hzzs_overlay_prefs"

        /** 透明度参数键 */
        private const val KEY_ALPHA = "overlay_alpha"

        /** 圆角半径参数键 */
        private const val KEY_RADIUS = "overlay_radius"

        /** 缩放系数参数键 */
        private const val KEY_SCALE_RATIO = "overlay_scale_ratio"

        /** 自动操作开关参数键 */
        private const val KEY_AUTO_OP_ENABLED = "auto_op_enabled"

        /** 自动操作延迟参数键（SeekBar progress 0~50，映射到 0~500ms） */
        private const val KEY_AUTO_OP_DELAY = "auto_op_delay"
    }

    private val appContext get() = context.applicationContext
    private val displayMetrics get() = appContext.resources.displayMetrics
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 透明度滑块 */
    private var alphaSlider: SeekBar? = view.findViewById(R.id.overlayAlphaSlider)

    /** 透明度显示文本 */
    private var alphaValue: TextView? = view.findViewById(R.id.overlayAlphaValue)

    /** 自动操作开关 */
    private var autoOpSwitch: SwitchCompat? = view.findViewById(R.id.overlayAutoOpSwitch)

    /** 自动操作状态文本 */
    private var autoOpStatus: TextView? = view.findViewById(R.id.overlayAutoOpStatus)

    /** 自动操作延迟滑块 */
    private var autoOpDelaySlider: SeekBar? = view.findViewById(R.id.overlayAutoOpDelaySlider)

    /** 自动操作延迟值显示文本 */
    private var autoOpDelayValue: TextView? = view.findViewById(R.id.overlayAutoOpDelayValue)

    /**
     * 绑定所有设置控件。
     *
     * 此方法应在 View inflate 完成后调用，
     * 完成透明度滑块、自动操作开关/延迟滑块的绑定和参数恢复。
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
     */
    private fun bindAlphaSlider() {
        alphaSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val alpha = progress / 100f
                view.alpha = alpha
                alphaValue?.text = "$progress%"
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
     */
    private fun bindAutoOpControls() {
        // 从 SharedPreferences 恢复设置
        val autoOpEnabled = prefs.getBoolean(KEY_AUTO_OP_ENABLED, false)
        val autoOpDelayProgress = prefs.getInt(KEY_AUTO_OP_DELAY, 10) // 0~50 → 0~500ms

        autoOpSwitch?.isChecked = autoOpEnabled
        autoOpDelaySlider?.progress = autoOpDelayProgress
        updateAutoOpStatus(autoOpEnabled)

        // 更新延迟显示文本
        autoOpDelayValue?.text = "${autoOpDelayProgress * 10} ms"

        // 绑定自动操作开关
        autoOpSwitch?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_OP_ENABLED, isChecked).apply()
            updateAutoOpStatus(isChecked)
        }

        // 绑定延迟滑块
        autoOpDelaySlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val ms = progress * 10
                autoOpDelayValue?.text = "$ms ms"
                prefs.edit().putInt(KEY_AUTO_OP_DELAY, progress).apply()
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
     * 恢复内容：
     * 1. 透明度（alpha）— 直接设置 view.alpha
     * 2. 圆角半径（radiusDp）— 转换为 px 后通过 GradientDrawable 设置
     * 3. 透明度滑块的进度值和显示文本
     * 4. 悬浮窗缩放系数（scaleRatio）— 直接设置 view.layoutParams.width
     *
     * @param baseWidth 基础宽度（px），用于缩放计算
     * @param applyCornerRadius 是否应用圆角（默认 true）
     */
    fun restoreAll(
        baseWidth: Int,
        applyCornerRadius: Boolean = true,
    ) {
        // 恢复透明度
        val alpha = prefs.getFloat(KEY_ALPHA, 1.0f)
        view.alpha = alpha
        alphaSlider?.progress = (alpha * 100).toInt()
        alphaValue?.text = "${alphaSlider?.progress}%"

        // 恢复圆角
        if (applyCornerRadius) {
            val radiusDp = prefs.getInt(KEY_RADIUS, 20)
            val radiusPx = (radiusDp * displayMetrics.density).roundToInt()
            applyOverlayCornerRadius(radiusPx)
        }

        // 恢复缩放系数
        val savedScale = prefs.getFloat(KEY_SCALE_RATIO, 1f)
        if (savedScale != 1f) {
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
     * @return 缩放系数
     */
    fun getCurrentScaleRatio(): Float {
        return prefs.getFloat(KEY_SCALE_RATIO, 1f)
    }
}
