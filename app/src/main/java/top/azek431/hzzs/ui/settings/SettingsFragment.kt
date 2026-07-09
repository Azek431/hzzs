// 火崽崽助手（HZZS）视觉识别设置 — 分区设置 Fragment。
//
// 职责：
// - 根据 Section 类型渲染对应的参数列表
// - 管理当前参数的内存值（currentValues）
// - 将用户修改同步回 SharedPreferences
//
// 设计原因：
// - 参数定义委托给 ParamBuilder
// - 控件渲染委托给 ParamRenderer
// - 本类只负责数据加载、参数构建、渲染和同步

package top.azek431.hzzs.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import top.azek431.hzzs.R

/**
 * 设置分区 Fragment。
 *
 * 所有分区共用同一个 Fragment 类，通过 viewType 区分渲染内容。
 * 每个分区包含：
 * - 顶部标题栏（sectionTitleText）
 * - 参数列表（ScrollView + LinearLayout）
 *
 * 数据流向：
 * 1. onCreate 时从 SharedPreferences 读取当前值 → 填充到 currentValues
 * 2. ParamBuilder 根据 section 构建 paramDefs
 * 3. ParamRenderer 根据 paramDefs 渲染控件
 * 4. 用户修改控件 → 实时更新 currentValues
 * 5. 点击"保存"时 → syncToPrefs() 批量写入 SharedPreferences
 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    companion object {
        private const val ARG_VIEW_TYPE = "view_type"

        /**
         * 创建 SettingsFragment 实例。
         *
         * @param viewType 分区类型
         * @param prefs SharedPreferences 实例（从 Activity 传入）
         * @return 新的 SettingsFragment 实例
         */
        fun createInstance(viewType: Section, prefs: android.content.SharedPreferences): SettingsFragment {
            return SettingsFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_VIEW_TYPE, viewType.ordinal)
                }
                _prefs = prefs
            }
        }
    }

    // ==================== View 引用 ====================

    /** 根布局 */
    private lateinit var rootView: View

    /** 滚动容器 */
    private lateinit var scrollView: android.widget.ScrollView

    /** 参数列表容器 */
    private lateinit var paramContainer: LinearLayout

    /** 分区标题 TextView */
    private lateinit var sectionTitleText: TextView

    // ==================== 数据状态 ====================

    /** SharedPreferences 实例（从 Activity 传入） */
    private lateinit var prefs: android.content.SharedPreferences

    /** 当前分区类型 */
    private lateinit var section: Section

    /** 当前内存值（保存前暂存于此） */
    private val currentValues = mutableMapOf<String, Any>()

    /** 参数定义列表 */
    private val paramDefs = mutableListOf<ParamDef>()

    /** 在 companion object.createInstance 中设置 */
    private var _prefs: android.content.SharedPreferences? = null

    // ==================== Fragment 生命周期 ====================

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rootView = view
        scrollView = view.findViewById(R.id.settingsScrollView)
        paramContainer = view.findViewById(R.id.paramContainer)
        sectionTitleText = view.findViewById(R.id.sectionTitle)

        prefs = _prefs!!
        section = Section.values()[requireArguments().getInt(ARG_VIEW_TYPE)]

        // 设置分区标题
        sectionTitleText.text = getString(section.titleRes)

        // 从 SharedPreferences 加载默认值到内存
        loadDefaultsFromPrefs()

        // 根据分区构建参数定义
        buildParams()

        // 渲染参数列表
        renderParams()
    }

    // ==================== 数据加载 ====================

    /**
     * 从 SharedPreferences 读取所有参数当前值到 currentValues。
     *
     * 使用 when 表达式按 key 读取不同类型的值。
     */
    private fun loadDefaultsFromPrefs() {
        currentValues["detectMode"] = prefs.getInt(VisionSettingsKeys.KEY_DETECT_MODE, VisionSettingsKeys.DEFAULT_DETECT_MODE)
        currentValues["screenshotMethod"] = prefs.getInt(VisionSettingsKeys.KEY_SCREENSHOT_METHOD, VisionSettingsKeys.DEFAULT_SCREENSHOT_METHOD)
        currentValues["loopInterval"] = prefs.getInt(VisionSettingsKeys.KEY_LOOP_INTERVAL_MS, VisionSettingsKeys.DEFAULT_LOOP_INTERVAL_MS)
        currentValues["drawStay"] = prefs.getInt(VisionSettingsKeys.KEY_DRAW_STAY_MS, VisionSettingsKeys.DEFAULT_DRAW_STAY_MS)
        currentValues["showScanLine"] = prefs.getBoolean(VisionSettingsKeys.KEY_SHOW_SCAN_LINE, VisionSettingsKeys.DEFAULT_SHOW_SCAN_LINE)
        currentValues["showValueLabel"] = prefs.getBoolean(VisionSettingsKeys.KEY_SHOW_VALUE_LABEL, VisionSettingsKeys.DEFAULT_SHOW_VALUE_LABEL)
        currentValues["showCenterPoint"] = prefs.getBoolean(VisionSettingsKeys.KEY_SHOW_CENTER_POINT, VisionSettingsKeys.DEFAULT_SHOW_CENTER_POINT)
        currentValues["drawColor"] = prefs.getInt(VisionSettingsKeys.KEY_DRAW_COLOR, VisionSettingsKeys.DEFAULT_DRAW_COLOR)
        currentValues["drawAlpha"] = prefs.getFloat(VisionSettingsKeys.KEY_DRAW_ALPHA, VisionSettingsKeys.DEFAULT_DRAW_ALPHA)
        currentValues["drawStrokeWidth"] = prefs.getFloat(VisionSettingsKeys.KEY_DRAW_STROKE_WIDTH, VisionSettingsKeys.DEFAULT_DRAW_STROKE_WIDTH)
        currentValues["playerWidthRatio"] = prefs.getFloat(VisionSettingsKeys.KEY_PLAYER_WIDTH_RATIO, VisionSettingsKeys.DEFAULT_PLAYER_WIDTH_RATIO)
        currentValues["playerHeightRatio"] = prefs.getFloat(VisionSettingsKeys.KEY_PLAYER_HEIGHT_RATIO, VisionSettingsKeys.DEFAULT_PLAYER_HEIGHT_RATIO)
        currentValues["greenBottleTop"] = prefs.getFloat(VisionSettingsKeys.KEY_GREEN_BOTTLE_TOP_RATIO, VisionSettingsKeys.DEFAULT_GREEN_BOTTLE_TOP_RATIO)
        currentValues["greenBottleBottom"] = prefs.getFloat(VisionSettingsKeys.KEY_GREEN_BOTTLE_BOTTOM_RATIO, VisionSettingsKeys.DEFAULT_GREEN_BOTTLE_BOTTOM_RATIO)
        currentValues["rgbRMin"] = prefs.getInt(VisionSettingsKeys.KEY_RGB_R_MIN, VisionSettingsKeys.DEFAULT_RGB_R_MIN)
        currentValues["rgbGMin"] = prefs.getInt(VisionSettingsKeys.KEY_RGB_G_MIN, VisionSettingsKeys.DEFAULT_RGB_G_MIN)
        currentValues["rgbBMin"] = prefs.getInt(VisionSettingsKeys.KEY_RGB_B_MIN, VisionSettingsKeys.DEFAULT_RGB_B_MIN)
        currentValues["rgbRMax"] = prefs.getInt(VisionSettingsKeys.KEY_RGB_R_MAX, VisionSettingsKeys.DEFAULT_RGB_R_MAX)
        currentValues["rgbGMax"] = prefs.getInt(VisionSettingsKeys.KEY_RGB_G_MAX, VisionSettingsKeys.DEFAULT_RGB_G_MAX)
        currentValues["rgbBMax"] = prefs.getInt(VisionSettingsKeys.KEY_RGB_B_MAX, VisionSettingsKeys.DEFAULT_RGB_B_MAX)
        currentValues["minSegmentWidth"] = prefs.getInt(VisionSettingsKeys.KEY_MIN_SEGMENT_WIDTH, VisionSettingsKeys.DEFAULT_MIN_SEGMENT_WIDTH)
        currentValues["gapMergeWidth"] = prefs.getInt(VisionSettingsKeys.KEY_GAP_MERGE_WIDTH, VisionSettingsKeys.DEFAULT_GAP_MERGE_WIDTH)
        currentValues["detectionPadding"] = prefs.getInt(VisionSettingsKeys.KEY_DETECTION_PADDING, VisionSettingsKeys.DEFAULT_DETECTION_PADDING)
        currentValues["confidenceThreshold"] = prefs.getFloat(VisionSettingsKeys.KEY_CONFIDENCE_THRESHOLD, VisionSettingsKeys.DEFAULT_CONFIDENCE_THRESHOLD)
        currentValues["logEnabled"] = prefs.getBoolean(VisionSettingsKeys.KEY_LOG_ENABLED, VisionSettingsKeys.DEFAULT_LOG_ENABLED)
        currentValues["debugMode"] = prefs.getBoolean(VisionSettingsKeys.KEY_DEBUG_MODE, VisionSettingsKeys.DEFAULT_DEBUG_MODE)
    }

    // ==================== 参数构建与渲染 ====================

    /** 根据分区构建参数定义列表 */
    private fun buildParams() {
        paramDefs.clear()
        val builder = ParamBuilder(requireContext())
        paramDefs.addAll(builder.build(section))
    }

    /** 渲染所有参数控件到 paramContainer */
    private fun renderParams() {
        val renderer = ParamRenderer(requireContext(), currentValues, paramContainer)
        for (def in paramDefs) {
            renderer.render(def)?.let { paramContainer.addView(it) }
        }
    }

    // ==================== 同步到 SharedPreferences ====================

    /**
     * 将当前内存中的所有参数值写入 SharedPreferences。
     *
     * 此方法在点击"保存"按钮时调用。
     * 按参数类型分类写入，避免类型转换错误。
     */
    fun syncToPrefs() {
        val editor = prefs.edit()

        // SeekBarInt 参数
        currentValues["loopInterval"]?.let { editor.putInt(VisionSettingsKeys.KEY_LOOP_INTERVAL_MS, it as Int) }
        currentValues["drawStay"]?.let { editor.putInt(VisionSettingsKeys.KEY_DRAW_STAY_MS, it as Int) }
        currentValues["minSegmentWidth"]?.let { editor.putInt(VisionSettingsKeys.KEY_MIN_SEGMENT_WIDTH, it as Int) }
        currentValues["gapMergeWidth"]?.let { editor.putInt(VisionSettingsKeys.KEY_GAP_MERGE_WIDTH, it as Int) }
        currentValues["detectionPadding"]?.let { editor.putInt(VisionSettingsKeys.KEY_DETECTION_PADDING, it as Int) }
        currentValues["detectMode"]?.let { editor.putInt(VisionSettingsKeys.KEY_DETECT_MODE, it as Int) }
        currentValues["screenshotMethod"]?.let { editor.putInt(VisionSettingsKeys.KEY_SCREENSHOT_METHOD, it as Int) }

        // SeekBarFloat 参数
        currentValues["drawAlpha"]?.let { editor.putFloat(VisionSettingsKeys.KEY_DRAW_ALPHA, it as Float) }
        currentValues["drawStrokeWidth"]?.let { editor.putFloat(VisionSettingsKeys.KEY_DRAW_STROKE_WIDTH, it as Float) }
        currentValues["playerWidthRatio"]?.let { editor.putFloat(VisionSettingsKeys.KEY_PLAYER_WIDTH_RATIO, it as Float) }
        currentValues["playerHeightRatio"]?.let { editor.putFloat(VisionSettingsKeys.KEY_PLAYER_HEIGHT_RATIO, it as Float) }
        currentValues["greenBottleTop"]?.let { editor.putFloat(VisionSettingsKeys.KEY_GREEN_BOTTLE_TOP_RATIO, it as Float) }
        currentValues["greenBottleBottom"]?.let { editor.putFloat(VisionSettingsKeys.KEY_GREEN_BOTTLE_BOTTOM_RATIO, it as Float) }
        currentValues["confidenceThreshold"]?.let { editor.putFloat(VisionSettingsKeys.KEY_CONFIDENCE_THRESHOLD, it as Float) }

        // Switch 参数
        currentValues["showScanLine"]?.let { editor.putBoolean(VisionSettingsKeys.KEY_SHOW_SCAN_LINE, it as Boolean) }
        currentValues["showValueLabel"]?.let { editor.putBoolean(VisionSettingsKeys.KEY_SHOW_VALUE_LABEL, it as Boolean) }
        currentValues["showCenterPoint"]?.let { editor.putBoolean(VisionSettingsKeys.KEY_SHOW_CENTER_POINT, it as Boolean) }
        currentValues["logEnabled"]?.let { editor.putBoolean(VisionSettingsKeys.KEY_LOG_ENABLED, it as Boolean) }
        currentValues["debugMode"]?.let { editor.putBoolean(VisionSettingsKeys.KEY_DEBUG_MODE, it as Boolean) }

        // ColorPicker 参数
        currentValues["drawColor"]?.let { editor.putInt(VisionSettingsKeys.KEY_DRAW_COLOR, it as Int) }

        // RGB Threshold 参数
        currentValues["rgbRMin"]?.let { editor.putInt(VisionSettingsKeys.KEY_RGB_R_MIN, it as Int) }
        currentValues["rgbGMin"]?.let { editor.putInt(VisionSettingsKeys.KEY_RGB_G_MIN, it as Int) }
        currentValues["rgbBMin"]?.let { editor.putInt(VisionSettingsKeys.KEY_RGB_B_MIN, it as Int) }
        currentValues["rgbRMax"]?.let { editor.putInt(VisionSettingsKeys.KEY_RGB_R_MAX, it as Int) }
        currentValues["rgbGMax"]?.let { editor.putInt(VisionSettingsKeys.KEY_RGB_G_MAX, it as Int) }
        currentValues["rgbBMax"]?.let { editor.putInt(VisionSettingsKeys.KEY_RGB_B_MAX, it as Int) }

        editor.apply()
    }
}
