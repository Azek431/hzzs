// 火崽崽助手（HZZS）视觉识别设置 Activity。
//
// 职责：
// - 提供视觉识别与 HUD 绘制参数的可调节设置界面
// - 所有参数通过 VisionSettingsKeys 管理的 SharedPreferences 持久化
// - 支持范围校验、一键恢复默认值
//
// UI 结构：
// 使用 ViewPager2 + Material Tabs 分为五个分区：
//   1. 识别与截图 — 识别模式、截图方式、循环间隔、绘制停留时间
//   2. HUD 显示 — 扫描线/数值标签/中心点开关、绘制颜色/透明度/线宽
//   3. 玩家与绿瓶 — 玩家宽高比例、绿瓶上下边界比例
//   4. 检测参数 — RGB 阈值、最小段宽、缺口合并、padding、置信度
//   5. 调试选项 — 日志开关、调试模式
//
// 底部固定"恢复默认值"和"保存"按钮。

package top.azek431.hzzs.ui.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import top.azek431.hzzs.R
import top.azek431.hzzs.ui.settings.SettingsFragment.Companion.createInstance
import top.azek431.hzzs.ui.settings.VisionSettingsKeys.resetAll
import top.azek431.hzzs.ui.settings.VisionSettingsKeys.PREFS_NAME

// ============================================================================
//  SettingsActivity — 设置入口
// ============================================================================

/**
 * 视觉识别设置页面。
 *
 * 采用全屏 Activity 风格（NoActionBar 主题），顶部 TabLayout 分区导航，
 * 底部固定操作栏（恢复默认 + 保存）。
 */
class VisionSettingsActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var bottomBar: View
    private lateinit var btnReset: MaterialButton
    private lateinit var btnSave: MaterialButton

    private val tabTitles = arrayOf(
        "识别与截图",
        "HUD 显示",
        "玩家与绿瓶",
        "检测参数",
        "调试选项",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vision_settings)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_overlay_preview_close)
            setTitle(R.string.settings_title)
        }

        cacheViews()
        setupViewPager()
        bindActions()
        applyTheme()
    }

    // ==================== View 缓存 ====================

    private fun cacheViews() {
        tabLayout = findViewById(R.id.tabLayout)
            ?: throw IllegalStateException("tabLayout not found")
        viewPager = findViewById(R.id.viewPager)
            ?: throw IllegalStateException("viewPager not found")
        bottomBar = findViewById(R.id.bottomBar)
            ?: throw IllegalStateException("bottomBar not found")
        btnReset = findViewById(R.id.btnReset)
            ?: throw IllegalStateException("btnReset not found")
        btnSave = findViewById(R.id.btnSave)
            ?: throw IllegalStateException("btnSave not found")
    }

    // ==================== ViewPager + Tabs ====================

    private fun setupViewPager() {
        val pagerAdapter = SettingsPagerAdapter(this)
        viewPager.adapter = pagerAdapter

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val fragments = listOf(
            createInstance(Section.RECOGNITION, prefs),
            createInstance(Section.HUD_DISPLAY, prefs),
            createInstance(Section.PLAYER_BOTTLE, prefs),
            createInstance(Section.DETECTION_PARAMS, prefs),
            createInstance(Section.DEBUG_OPTIONS, prefs),
        )
        pagerAdapter.setFragments(fragments)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()
    }

    // ==================== 按钮交互 ====================

    private fun bindActions() {
        btnReset.setOnClickListener {
            showResetConfirmation()
        }

        btnSave.setOnClickListener {
            syncAllFragments()
            showToast(R.string.settings_saved)
            finish()
        }
    }

    /** 将 ViewPager 中所有 Fragment 的参数同步回 SharedPreferences */
    private fun syncAllFragments() {
        val pagerAdapter = viewPager.adapter as SettingsPagerAdapter
        for (i in 0 until pagerAdapter.itemCount) {
            val fragment = pagerAdapter.getItem(i)
            fragment?.syncToPrefs()
        }
    }

    // ==================== 主题适配 ====================

    private fun applyTheme() {
        bottomBar.setBackgroundColor(
            ContextCompat.getColor(this, R.color.surface_container_high)
        )
        btnSave.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_primary))
        btnSave.setTextColor(ContextCompat.getColor(this, R.color.brand_on_primary))
        btnReset.setBackgroundColor(ContextCompat.getColor(this, R.color.surface))
        btnReset.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
        btnReset.setStrokeColor(
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.outline))
        )
    }

    // ==================== 恢复默认确认 ====================

    private fun showResetConfirmation() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_reset_title)
            .setMessage(R.string.settings_reset_message)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                resetAll(this)
                showToast(R.string.settings_reset_done)
                reloadAllFragments()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun reloadAllFragments() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val fragments = listOf(
            createInstance(Section.RECOGNITION, prefs),
            createInstance(Section.HUD_DISPLAY, prefs),
            createInstance(Section.PLAYER_BOTTLE, prefs),
            createInstance(Section.DETECTION_PARAMS, prefs),
            createInstance(Section.DEBUG_OPTIONS, prefs),
        )
        (viewPager.adapter as SettingsPagerAdapter).setFragments(fragments)
        viewPager.currentItem = 0
        tabLayout.selectTab(tabLayout.getTabAt(0))
    }

    // ==================== 工具方法 ====================

    private fun showToast(resId: Int) {
        android.widget.Toast.makeText(applicationContext, getString(resId), android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

// ============================================================================
//  Section 枚举 — 设置分区
// ============================================================================

/**
 * 设置分区枚举。
 *
 * 每个分区对应一个 SettingsFragment，Fragment 内部通过 viewType 决定渲染哪些控件。
 */
enum class Section(val titleRes: Int) {
    RECOGNITION(R.string.section_recognition),
    HUD_DISPLAY(R.string.section_hud_display),
    PLAYER_BOTTLE(R.string.section_player_bottle),
    DETECTION_PARAMS(R.string.section_detection_params),
    DEBUG_OPTIONS(R.string.section_debug_options),
}

// ============================================================================
//  SettingsPagerAdapter — ViewPager2 适配器（手动实现 FragmentStateAdapter）
// ============================================================================

/**
 * ViewPager2 适配器，管理五个设置分区 Fragment。
 *
 * 由于当前项目环境中 androidx.viewpager2 的 FragmentStateAdapter
 * 无法被 Kotlin 编译器解析，此处手动实现等效的 RecyclerView.Adapter。
 */
class SettingsPagerAdapter(activity: FragmentActivity) :
    androidx.recyclerview.widget.RecyclerView.Adapter<SettingsPagerAdapter.FragViewHolder>() {

    private val fragmentManager: FragmentManager = activity.supportFragmentManager
    private var fragments: MutableList<SettingsFragment> = mutableListOf()

    fun setFragments(list: List<SettingsFragment>) {
        // 先销毁旧的 Fragment
        val transaction = fragmentManager.beginTransaction()
        for (frag in fragments) {
            transaction.remove(frag)
        }
        transaction.commitNowAllowingStateLoss()
        fragments.clear()
        fragments.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): FragViewHolder {
        val container = android.widget.FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            id = viewType
        }
        return FragViewHolder(container)
    }

    override fun onBindViewHolder(holder: FragViewHolder, position: Int) {
        val frag = fragments[position]
        // 使用 FragmentManager 添加/显示 Fragment
        val transaction = fragmentManager.beginTransaction()
        val existing = fragmentManager.findFragmentByTag("f$position")
        if (existing != null) {
            transaction.show(existing)
        } else {
            transaction.replace(holder.container.id, frag, "f$position")
        }
        transaction.commitNowAllowingStateLoss()
        holder.container.tag = frag
    }

    override fun getItemCount() = fragments.size

    override fun getItemViewType(position: Int) = position + 1000 // 唯一 ID

    fun getItem(position: Int): SettingsFragment? {
        return if (position in fragments.indices) fragments[position] else null
    }

    class FragViewHolder(val container: android.widget.FrameLayout) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(container)
}

// ============================================================================
//  SettingsFragment — 通用设置 Fragment
// ============================================================================

/**
 * 设置分区 Fragment。
 *
 * 所有分区共用同一个 Fragment 类，通过 viewType 区分渲染内容。
 * 每个分区包含：
 * - 顶部标题栏（图标 + 文字）
 * - 参数列表（ScrollView + LinearLayout）
 * - 每个参数行：Label + 控件（Switch / SeekBar / EditText / Spinner / ColorView）
 *
 * 数据流向：
 * 1. onCreate 时从 SharedPreferences 读取当前值 → 填充到 UI 控件
 * 2. 用户修改控件 → 实时更新内存中的 currentValueMap
 * 3. 点击"保存"时 → syncToPrefs() 批量写入 SharedPreferences
 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    companion object {
        private const val ARG_VIEW_TYPE = "view_type"

        fun createInstance(viewType: Section, prefs: android.content.SharedPreferences): SettingsFragment {
            return SettingsFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_VIEW_TYPE, viewType.ordinal)
                }
                _prefs = prefs
            }
        }
    }

    private lateinit var rootView: View
    private lateinit var scrollView: android.widget.ScrollView
    private lateinit var paramContainer: LinearLayout
    private lateinit var sectionTitleText: TextView

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var section: Section

    // 当前内存值（保存前暂存于此）
    private val currentValues = mutableMapOf<String, Any>()

    // 参数定义列表（每个分区自行填充）
    private val paramDefs = mutableListOf<ParamDef>()

    // 在 companion object.createInstance 中设置
    private var _prefs: android.content.SharedPreferences? = null

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
        buildParamsForSection()

        // 渲染参数列表
        renderParams()
    }

    // ==================== 数据加载 ====================

    /** 从 SharedPreferences 读取所有参数当前值到 currentValues */
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

    // ==================== 分区参数构建 ====================

    private fun buildParamsForSection() {
        when (section) {
            Section.RECOGNITION -> buildRecognitionParams()
            Section.HUD_DISPLAY -> buildHudDisplayParams()
            Section.PLAYER_BOTTLE -> buildPlayerBottleParams()
            Section.DETECTION_PARAMS -> buildDetectionParams()
            Section.DEBUG_OPTIONS -> buildDebugParams()
        }
    }

    private fun buildRecognitionParams() {
        paramDefs.add(ParamDef.Spacer(8))
        paramDefs.add(ParamDef.Spinner(
            label = getString(R.string.settings_detect_mode),
            key = "detectMode",
            labels = arrayOf("离线模拟", "截图分析", "实时流"),
            defaultValue = VisionSettingsKeys.DEFAULT_DETECT_MODE,
            summary = "选择画面识别的模式"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.Spinner(
            label = getString(R.string.settings_screenshot_method),
            key = "screenshotMethod",
            labels = arrayOf("无障碍 takeScreenshot", "MediaProjection", "截屏服务"),
            defaultValue = VisionSettingsKeys.DEFAULT_SCREENSHOT_METHOD,
            summary = "选择截图采集方式"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarInt(
            label = getString(R.string.settings_loop_interval),
            key = "loopInterval",
            min = VisionSettingsKeys.MIN_LOOP_INTERVAL_MS,
            max = VisionSettingsKeys.MAX_LOOP_INTERVAL_MS,
            step = 10,
            defaultValue = VisionSettingsKeys.DEFAULT_LOOP_INTERVAL_MS,
            unit = "ms",
            summary = "循环识别间隔，推荐 50~200ms"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarInt(
            label = getString(R.string.settings_draw_stay),
            key = "drawStay",
            min = 50, max = 5000, step = 50,
            defaultValue = VisionSettingsKeys.DEFAULT_DRAW_STAY_MS,
            unit = "ms",
            summary = "单次绘制在屏幕上停留的时间"
        ))
    }

    private fun buildHudDisplayParams() {
        paramDefs.add(ParamDef.Spacer(8))

        paramDefs.add(ParamDef.Switch(
            key = "showScanLine",
            defaultValue = VisionSettingsKeys.DEFAULT_SHOW_SCAN_LINE,
            summary = getString(R.string.settings_show_scan_line) + " — 在 HUD 上绘制水平扫描线"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.Switch(
            key = "showValueLabel",
            defaultValue = VisionSettingsKeys.DEFAULT_SHOW_VALUE_LABEL,
            summary = getString(R.string.settings_show_value_label) + " — 在检测目标旁显示数值标签"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.Switch(
            key = "showCenterPoint",
            defaultValue = VisionSettingsKeys.DEFAULT_SHOW_CENTER_POINT,
            summary = getString(R.string.settings_show_center_point) + " — 在屏幕中心绘制十字准星"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.ColorPicker(
            label = getString(R.string.settings_draw_color),
            key = "drawColor",
            defaultValue = VisionSettingsKeys.DEFAULT_DRAW_COLOR,
            summary = "HUD 绘制元素的颜色"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarFloat(
            label = getString(R.string.settings_draw_alpha),
            key = "drawAlpha",
            min = 0f, max = 1f, step = 0.05f,
            defaultValue = VisionSettingsKeys.DEFAULT_DRAW_ALPHA,
            unit = "",
            formatPercent = true,
            summary = "绘制元素的透明度"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarFloat(
            label = getString(R.string.settings_draw_stroke_width),
            key = "drawStrokeWidth",
            min = 0.5f, max = 8f, step = 0.5f,
            defaultValue = VisionSettingsKeys.DEFAULT_DRAW_STROKE_WIDTH,
            unit = "px",
            summary = "绘制线条的粗细"
        ))
    }

    private fun buildPlayerBottleParams() {
        paramDefs.add(ParamDef.Spacer(8))

        paramDefs.add(ParamDef.SeekBarFloat(
            label = getString(R.string.settings_player_width_ratio),
            key = "playerWidthRatio",
            min = 0.01f, max = 0.99f, step = 0.01f,
            defaultValue = VisionSettingsKeys.DEFAULT_PLAYER_WIDTH_RATIO,
            unit = "",
            summary = "玩家宽度占屏幕宽度的比例"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarFloat(
            label = getString(R.string.settings_player_height_ratio),
            key = "playerHeightRatio",
            min = 0.01f, max = 0.99f, step = 0.01f,
            defaultValue = VisionSettingsKeys.DEFAULT_PLAYER_HEIGHT_RATIO,
            unit = "",
            summary = "玩家高度占屏幕高度的比例"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarFloat(
            label = getString(R.string.settings_green_bottle_top),
            key = "greenBottleTop",
            min = 0f, max = 1f, step = 0.05f,
            defaultValue = VisionSettingsKeys.DEFAULT_GREEN_BOTTLE_TOP_RATIO,
            unit = "",
            summary = "绿瓶检测上边界占扫描线的比例"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarFloat(
            label = getString(R.string.settings_green_bottle_bottom),
            key = "greenBottleBottom",
            min = 0f, max = 1f, step = 0.05f,
            defaultValue = VisionSettingsKeys.DEFAULT_GREEN_BOTTLE_BOTTOM_RATIO,
            unit = "",
            summary = "绿瓶检测下边界占扫描线的比例"
        ))
    }

    private fun buildDetectionParams() {
        paramDefs.add(ParamDef.Spacer(8))

        paramDefs.add(ParamDef.RGBThreshold(
            label = getString(R.string.settings_rgb_min),
            rKey = "rgbRMin", gKey = "rgbGMin", bKey = "rgbBMin",
            defaultValueR = VisionSettingsKeys.DEFAULT_RGB_R_MIN,
            defaultValueG = VisionSettingsKeys.DEFAULT_RGB_G_MIN,
            defaultValueB = VisionSettingsKeys.DEFAULT_RGB_B_MIN,
            summary = "RGB 颜色检测下限（0~255）"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.RGBThreshold(
            label = getString(R.string.settings_rgb_max),
            rKey = "rgbRMax", gKey = "rgbGMax", bKey = "rgbBMax",
            defaultValueR = VisionSettingsKeys.DEFAULT_RGB_R_MAX,
            defaultValueG = VisionSettingsKeys.DEFAULT_RGB_G_MAX,
            defaultValueB = VisionSettingsKeys.DEFAULT_RGB_B_MAX,
            summary = "RGB 颜色检测上限（0~255）"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarInt(
            label = getString(R.string.settings_min_segment_width),
            key = "minSegmentWidth",
            min = 1, max = 50, step = 1,
            defaultValue = VisionSettingsKeys.DEFAULT_MIN_SEGMENT_WIDTH,
            unit = "px",
            summary = "检测片段的最小宽度，低于此值的片段将被忽略"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarInt(
            label = getString(R.string.settings_gap_merge),
            key = "gapMergeWidth",
            min = 0, max = 100, step = 1,
            defaultValue = VisionSettingsKeys.DEFAULT_GAP_MERGE_WIDTH,
            unit = "px",
            summary = "相邻片段之间的最大缺口距离，小于此值将被合并"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarInt(
            label = getString(R.string.settings_detection_padding),
            key = "detectionPadding",
            min = 0, max = 200, step = 5,
            defaultValue = VisionSettingsKeys.DEFAULT_DETECTION_PADDING,
            unit = "px",
            summary = "检测区域向内收缩的像素数"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarFloat(
            label = getString(R.string.settings_confidence_threshold),
            key = "confidenceThreshold",
            min = 0f, max = 1f, step = 0.05f,
            defaultValue = VisionSettingsKeys.DEFAULT_CONFIDENCE_THRESHOLD,
            unit = "",
            formatPercent = true,
            summary = "检测结果的可信度阈值，低于此值视为无效检测"
        ))
    }

    private fun buildDebugParams() {
        paramDefs.add(ParamDef.Spacer(8))

        paramDefs.add(ParamDef.Label(getString(R.string.settings_log_enabled)))
        paramDefs.add(ParamDef.Switch(
            key = "logEnabled",
            defaultValue = VisionSettingsKeys.DEFAULT_LOG_ENABLED,
            summary = "在 Logcat 中输出识别过程日志"
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.Label(getString(R.string.settings_debug_mode)))
        paramDefs.add(ParamDef.Switch(
            key = "debugMode",
            defaultValue = VisionSettingsKeys.DEFAULT_DEBUG_MODE,
            summary = "启用调试模式：绘制额外调试信息（边界框、中心线等）"
        ))

        paramDefs.add(ParamDef.Spacer(24))
        paramDefs.add(ParamDef.Note(getString(R.string.settings_debug_note)))
    }

    // ==================== 渲染参数列表 ====================

    /**
     * 根据 paramDefs 列表渲染所有参数控件到 paramContainer。
     */
    private fun renderParams() {
        for (def in paramDefs) {
            val v = when (def) {
                is ParamDef.Spacer -> createSpacer(def.dp)
                is ParamDef.Label -> createLabel(def.text)
                is ParamDef.Switch -> createSwitchRow(def)
                is ParamDef.SeekBarInt -> createSeekBarIntRow(def)
                is ParamDef.SeekBarFloat -> createSeekBarFloatRow(def)
                is ParamDef.Spinner -> createSpinnerRow(def)
                is ParamDef.ColorPicker -> createColorPickerRow(def)
                is ParamDef.RGBThreshold -> createRGBThresholdRow(def)
                is ParamDef.Note -> createNote(def.text)
            }
            v?.let { paramContainer.addView(it) }
        }
    }

    // ==================== 控件工厂方法 ====================

    private fun createSpacer(dp: Int): View? {
        return View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(dp)
            )
        }
    }

    private fun createLabel(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(4))
        }
    }

    /** 创建 Switch 行 */
    private fun createSwitchRow(def: ParamDef.Switch): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_settings_row)
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(2)
                bottomMargin = dpToPx(2)
            }
        }

        val topRow = LinearLayout(row.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val infoLayout = LinearLayout(topRow.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = dpToPx(12) }
        }

        val label = TextView(requireContext()).apply {
            text = def.summary
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val switch = Switch(requireContext()).apply {
            isChecked = (currentValues[def.key] as Boolean) == true
            setOnCheckedChangeListener { _, checked ->
                currentValues[def.key] = checked
            }
        }

        infoLayout.addView(label)
        topRow.addView(infoLayout)
        topRow.addView(switch)
        row.addView(topRow)
        return row
    }

    /** 创建 SeekBarInt 行 */
    private fun createSeekBarIntRow(def: ParamDef.SeekBarInt): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_settings_row)
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(2)
                bottomMargin = dpToPx(2)
            }
        }

        // 第一行：标题 + 当前值
        val topRow = LinearLayout(row.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val label = TextView(requireContext()).apply {
            text = def.label
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = dpToPx(8) }
        }

        val valueText = TextView(requireContext()).apply {
            val defaultVal = def.defaultValue
            currentValues[def.key] = defaultVal
            text = "$defaultVal${def.unit}"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_primary))
            textSize = 14f
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        topRow.addView(label)
        topRow.addView(valueText)
        row.addView(topRow)

        // SeekBar
        val seekBar = SeekBar(requireContext()).apply {
            max = ((def.max - def.min) / def.step).toFloat().toInt()
            progress = ((def.defaultValue - def.min) / def.step).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = def.min + progress * def.step
                    currentValues[def.key] = value
                    valueText.text = "$value${def.unit}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            progressTintList = ContextCompat.getColorStateList(requireContext(), R.color.brand_primary)
            progressBackgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.outline_variant)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(6) }
        }

        // 摘要
        val summary = TextView(requireContext()).apply {
            text = def.summary
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_variant))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(4) }
        }

        row.addView(seekBar)
        row.addView(summary)
        return row
    }

    /** 创建 SeekBarFloat 行 */
    private fun createSeekBarFloatRow(def: ParamDef.SeekBarFloat): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_settings_row)
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(2)
                bottomMargin = dpToPx(2)
            }
        }

        val topRow = LinearLayout(row.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val label = TextView(requireContext()).apply {
            text = def.label
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = dpToPx(8) }
        }

        val valueText = TextView(requireContext()).apply {
            val defaultVal = def.defaultValue
            currentValues[def.key] = defaultVal
            text = formatFloatValue(defaultVal, def)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_primary))
            textSize = 14f
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        topRow.addView(label)
        topRow.addView(valueText)
        row.addView(topRow)

        val seekBar = SeekBar(requireContext()).apply {
            val stepFloat = def.step
            val range = def.max - def.min
            val steps = (range / stepFloat).toInt()
            max = steps
            progress = ((def.defaultValue - def.min) / stepFloat).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = def.min + progress * stepFloat
                    val rounded = kotlin.math.round(value * 100f) / 100f
                    currentValues[def.key] = rounded
                    valueText.text = formatFloatValue(rounded, def)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            progressTintList = ContextCompat.getColorStateList(requireContext(), R.color.brand_primary)
            progressBackgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.outline_variant)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(6) }
        }

        val summary = TextView(requireContext()).apply {
            text = def.summary
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_variant))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(4) }
        }

        row.addView(seekBar)
        row.addView(summary)
        return row
    }

    private fun formatFloatValue(value: Float, def: ParamDef.SeekBarFloat): String {
        return if (def.formatPercent) "${(value * 100).toInt()}%" else String.format("%.2f%s", value, def.unit)
    }

    /** 创建 Spinner 行 */
    private fun createSpinnerRow(def: ParamDef.Spinner): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_settings_row)
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(2)
                bottomMargin = dpToPx(2)
            }
        }

        val label = TextView(requireContext()).apply {
            text = def.label
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(6) }
        }

        val spinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, def.labels)
            setSelection(def.defaultValue)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    currentValues[def.key] = position
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val summary = TextView(requireContext()).apply {
            text = def.summary
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_variant))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(8) }
        }

        row.addView(label)
        row.addView(spinner)
        row.addView(summary)
        return row
    }

    /** 创建 ColorPicker 行 */
    private fun createColorPickerRow(def: ParamDef.ColorPicker): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_settings_row)
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(2)
                bottomMargin = dpToPx(2)
            }
        }

        val colorView = View(requireContext()).apply {
            val defaultColor = def.defaultValue
            currentValues[def.key] = defaultColor
            setBackgroundColor(defaultColor)
            layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44)).apply {
                marginEnd = dpToPx(14)
                gravity = android.view.Gravity.CENTER
            }
            elevation = dpToPx(2f).toFloat()
            foreground = ContextCompat.getDrawable(requireContext(), R.drawable.bg_settings_row)?.constantState?.newDrawable()
        }

        val infoLayout = LinearLayout(row.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        val label = TextView(requireContext()).apply {
            text = def.label
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }

        val hexText = TextView(requireContext()).apply {
            val c = def.defaultValue
            text = String.format("#%06X", 0xFFFFFF and c)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_primary))
            textSize = 12f
        }

        val summary = TextView(requireContext()).apply {
            text = def.summary
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_variant))
            textSize = 11f
            setMaxLines(1)
            ellipsize = TextUtils.TruncateAt.END
        }

        infoLayout.addView(label)
        infoLayout.addView(hexText)
        infoLayout.addView(summary)

        colorView.setOnClickListener {
            showColorPickerDialog(def, colorView, hexText)
        }

        row.addView(colorView)
        row.addView(infoLayout)
        return row
    }

    /** 显示颜色选择对话框 */
    private fun showColorPickerDialog(def: ParamDef.ColorPicker, colorView: View, hexText: TextView) {
        val currentColor = (currentValues[def.key] as Int)
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(currentColor, hsv)

        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(R.string.settings_select_color)

        val hsvLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
        }

        val colorPreview = View(requireContext()).apply {
            setBackgroundColor(currentColor)
            layoutParams = LinearLayout.LayoutParams(dpToPx(60), dpToPx(60)).apply {
                marginEnd = dpToPx(16)
                gravity = android.view.Gravity.CENTER
            }
        }

        val hueBar = SeekBar(requireContext()).apply {
            max = 360
            progress = (hsv[0] * 360).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(12) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    hsv[0] = progress.toFloat()
                    val newColor = android.graphics.Color.HSVToColor(hsv)
                    colorPreview.setBackgroundColor(newColor)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            progressTintList = ContextCompat.getColorStateList(requireContext(), R.color.brand_primary)
            progressBackgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.outline_variant)
        }

        hsvLayout.addView(colorPreview)
        hsvLayout.addView(hueBar)
        builder.setView(hsvLayout)

        builder.setPositiveButton(R.string.action_confirm) { _, _ ->
            val selectedColor = android.graphics.Color.HSVToColor(hsv)
            currentValues[def.key] = selectedColor
            colorView.setBackgroundColor(selectedColor)
            hexText.text = String.format("#%06X", 0xFFFFFF and selectedColor)
        }
        builder.setNegativeButton(R.string.action_cancel, null)
        builder.show()
    }

    /** 创建 RGB 阈值行 */
    private fun createRGBThresholdRow(def: ParamDef.RGBThreshold): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_settings_row)
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(2)
                bottomMargin = dpToPx(2)
            }
        }

        val label = TextView(requireContext()).apply {
            text = def.label
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
        }

        val channels = arrayOf(
            Triple("R", def.rKey, def.defaultValueR),
            Triple("G", def.gKey, def.defaultValueG),
            Triple("B", def.bKey, def.defaultValueB),
        )

        for ((channel, key, defaultVal) in channels) {
            val channelRow = LinearLayout(row.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpToPx(6) }
            }

            val channelLabel = TextView(requireContext()).apply {
                text = channel
                setTextColor(getChannelColor(channel))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(32), ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dpToPx(8) }
                gravity = android.view.Gravity.CENTER
            }

            val editText = EditText(requireContext()).apply {
                hint = "0~255"
                setText(defaultVal.toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                maxWidth = dpToPx(60)
                currentValues[key] = defaultVal
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
                setHintTextColor(ContextCompat.getColor(requireContext(), R.color.outline))
                setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(ContextCompat.getColor(requireContext(), R.color.surface_container_high))
                    setCornerRadius(dpToPx(4).toFloat())
                    setStroke(1, ContextCompat.getColor(requireContext(), R.color.outline_variant))
                }
                doAfterTextChanged { editable ->
                    val value = editable?.toString()?.toIntOrNull()
                    if (value != null && value in 0..255) {
                        currentValues[key] = value
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = dpToPx(8) }
            }

            val seekBar = SeekBar(requireContext()).apply {
                max = 255
                progress = defaultVal
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f
                )
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        currentValues[key] = progress
                        editText.setText(progress.toString())
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
                progressTintList = ContextCompat.getColorStateList(requireContext(), getChannelColor(channel))
                progressBackgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.outline_variant)
            }

            channelRow.addView(channelLabel)
            channelRow.addView(editText)
            channelRow.addView(seekBar)
            row.addView(channelRow)
        }

        val summary = TextView(requireContext()).apply {
            text = def.summary
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_variant))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(8) }
        }

        row.addView(label)
        row.addView(summary)
        return row
    }

    /** 创建提示文字 */
    private fun createNote(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_variant))
            textSize = 11f
            setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(requireContext(), R.color.status_preparing_container))
                setCornerRadius(dpToPx(8).toFloat())
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
                bottomMargin = dpToPx(4)
            }
        }
    }

    /** 获取通道颜色 */
    private fun getChannelColor(channel: String): Int {
        return when (channel) {
            "R" -> Color.parseColor("#FF5252")
            "G" -> Color.parseColor("#4CAF50")
            "B" -> Color.parseColor("#448AFF")
            else -> Color.parseColor("#9E9E9E")
        }
    }

    // ==================== 同步到 SharedPreferences ====================

    /**
     * 将当前内存中的所有参数值写入 SharedPreferences。
     *
     * 此方法在点击"保存"按钮时调用。
     */
    fun syncToPrefs() {
        val editor = prefs.edit()

        // SeekBarInt 参数
        currentValues["loopInterval"]?.let { editor.putInt(VisionSettingsKeys.KEY_LOOP_INTERVAL_MS, it as Int) }
        currentValues["drawStay"]?.let { editor.putInt(VisionSettingsKeys.KEY_DRAW_STAY_MS, it as Int) }
        currentValues["minSegmentWidth"]?.let { editor.putInt(VisionSettingsKeys.KEY_MIN_SEGMENT_WIDTH, it as Int) }
        currentValues["gapMergeWidth"]?.let { editor.putInt(VisionSettingsKeys.KEY_GAP_MERGE_WIDTH, it as Int) }
        currentValues["detectionPadding"]?.let { editor.putInt(VisionSettingsKeys.KEY_DETECTION_PADDING, it as Int) }

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

        // Spinner 参数
        currentValues["detectMode"]?.let { editor.putInt(VisionSettingsKeys.KEY_DETECT_MODE, it as Int) }
        currentValues["screenshotMethod"]?.let { editor.putInt(VisionSettingsKeys.KEY_SCREENSHOT_METHOD, it as Int) }

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

    // ==================== 工具方法 ====================

    private fun dpToPx(dp: Float): Int {
        return (dp * requireContext().resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * requireContext().resources.displayMetrics.density + 0.5f).toInt()
    }
}

// ============================================================================
//  ParamDef — 参数定义数据类型
// ============================================================================

/**
 * 参数定义的密封类层次。
 *
 * 每种子类对应一种 UI 控件类型，renderParams() 根据类型渲染不同控件。
 */
sealed class ParamDef {
    /** 空白间距（dp） */
    data class Spacer(val dp: Int) : ParamDef()

    /** 参数标题 */
    data class Label(val text: String) : ParamDef()

    /** 开关控件 */
    data class Switch(
        val key: String,
        val defaultValue: Boolean,
        val summary: String,
    ) : ParamDef()

    /** 整数 SeekBar */
    data class SeekBarInt(
        val label: String,
        val key: String,
        val min: Int,
        val max: Int,
        val step: Int,
        val defaultValue: Int,
        val unit: String,
        val summary: String,
    ) : ParamDef()

    /** 浮点数 SeekBar */
    data class SeekBarFloat(
        val label: String,
        val key: String,
        val min: Float,
        val max: Float,
        val step: Float,
        val defaultValue: Float,
        val unit: String,
        val summary: String,
        val formatPercent: Boolean = false,
    ) : ParamDef()

    /** 下拉选择框 */
    data class Spinner(
        val label: String,
        val key: String,
        val labels: Array<out String>,
        val defaultValue: Int,
        val summary: String,
    ) : ParamDef()

    /** 颜色选择器 */
    data class ColorPicker(
        val label: String,
        val key: String,
        val defaultValue: Int,
        val summary: String,
    ) : ParamDef()

    /** RGB 三色阈值 */
    data class RGBThreshold(
        val label: String,
        val rKey: String,
        val gKey: String,
        val bKey: String,
        val defaultValueR: Int,
        val defaultValueG: Int,
        val defaultValueB: Int,
        val summary: String,
    ) : ParamDef()

    /** 提示文字 */
    data class Note(val text: String) : ParamDef()
}
