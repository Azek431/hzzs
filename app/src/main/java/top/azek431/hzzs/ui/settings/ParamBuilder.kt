// 火崽崽助手（HZZS）视觉识别设置 — 分区参数构建器。
//
// 职责：
// - 为每个设置分区构建 ParamDef 列表
// - 包含参数元数据（标签、默认值、范围、单位、摘要说明）
//
// 设计原因：
// - 将参数定义与渲染逻辑分离
// - 每个分区独立构建，便于维护和扩展
// - 参数定义集中管理，新增设置项只需修改对应方法

package top.azek431.hzzs.ui.settings

import android.content.Context
import top.azek431.hzzs.R

/**
 * 分区参数构建器。
 *
 * 根据分区类型构建对应的 ParamDef 列表。
 * 所有默认值从 VisionSettingsKeys 读取，确保单一数据源。
 *
 * @param context 上下文（用于获取字符串资源）
 */
class ParamBuilder(private val context: Context) {

    /** 当前分区的所有参数定义 */
    private val paramDefs = mutableListOf<ParamDef>()

    // ==================== 分区参数构建入口 ====================

    /**
     * 根据分区类型构建参数列表。
     *
     * @param section 分区枚举
     * @return 参数定义列表
     */
    fun build(section: Section): List<ParamDef> {
        paramDefs.clear()
        when (section) {
            Section.RECOGNITION -> buildRecognitionParams()
            Section.HUD_DISPLAY -> buildHudDisplayParams()
            Section.PLAYER_BOTTLE -> buildPlayerBottleParams()
            Section.DETECTION_PARAMS -> buildDetectionParams()
            Section.DEBUG_OPTIONS -> buildDebugParams()
        }
        return paramDefs.toList()
    }

    // ==================== 识别与截图分区 ====================

    /**
     * 构建"识别与截图"分区参数。
     *
     * 包含：识别模式、截图方式、循环间隔、绘制停留时间。
     */
    private fun buildRecognitionParams() {
        paramDefs.add(ParamDef.Spacer(8))
        paramDefs.add(ParamDef.Spinner(
            label = context.getString(R.string.settings_detect_mode),
            key = "detectMode",
            labels = arrayOf("离线模拟", "截图分析", "实时流"),
            defaultValue = VisionSettingsKeys.DEFAULT_DETECT_MODE,
            summary = "选择画面识别的模式",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.Spinner(
            label = context.getString(R.string.settings_screenshot_method),
            key = "screenshotMethod",
            labels = arrayOf("无障碍 takeScreenshot", "MediaProjection", "截屏服务"),
            defaultValue = VisionSettingsKeys.DEFAULT_SCREENSHOT_METHOD,
            summary = "选择截图采集方式",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarInt(
            label = context.getString(R.string.settings_loop_interval),
            key = "loopInterval",
            min = VisionSettingsKeys.MIN_LOOP_INTERVAL_MS,
            max = VisionSettingsKeys.MAX_LOOP_INTERVAL_MS,
            step = 10,
            defaultValue = VisionSettingsKeys.DEFAULT_LOOP_INTERVAL_MS,
            unit = "ms",
            summary = "循环识别间隔，推荐 50~200ms",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarInt(
            label = context.getString(R.string.settings_draw_stay),
            key = "drawStay",
            min = 50, max = 5000, step = 50,
            defaultValue = VisionSettingsKeys.DEFAULT_DRAW_STAY_MS,
            unit = "ms",
            summary = "单次绘制在屏幕上停留的时间",
        ))
    }

    // ==================== HUD 显示分区 ====================

    /**
     * 构建"HUD 显示"分区参数。
     *
     * 包含：扫描线开关、数值标签开关、中心点开关、
     *       绘制颜色、透明度、线宽。
     */
    private fun buildHudDisplayParams() {
        paramDefs.add(ParamDef.Spacer(8))
        paramDefs.add(ParamDef.Switch(
            key = "showScanLine",
            defaultValue = VisionSettingsKeys.DEFAULT_SHOW_SCAN_LINE,
            summary = context.getString(R.string.settings_show_scan_line) + " — 在 HUD 上绘制水平扫描线",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.Switch(
            key = "showValueLabel",
            defaultValue = VisionSettingsKeys.DEFAULT_SHOW_VALUE_LABEL,
            summary = context.getString(R.string.settings_show_value_label) + " — 在检测目标旁显示数值标签",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.Switch(
            key = "showCenterPoint",
            defaultValue = VisionSettingsKeys.DEFAULT_SHOW_CENTER_POINT,
            summary = context.getString(R.string.settings_show_center_point) + " — 在屏幕中心绘制十字准星",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.ColorPicker(
            label = context.getString(R.string.settings_draw_color),
            key = "drawColor",
            defaultValue = VisionSettingsKeys.DEFAULT_DRAW_COLOR,
            summary = "HUD 绘制元素的颜色",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarFloat(
            label = context.getString(R.string.settings_draw_alpha),
            key = "drawAlpha",
            min = 0f, max = 1f, step = 0.05f,
            defaultValue = VisionSettingsKeys.DEFAULT_DRAW_ALPHA,
            unit = "",
            formatPercent = true,
            summary = "绘制元素的透明度",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarFloat(
            label = context.getString(R.string.settings_draw_stroke_width),
            key = "drawStrokeWidth",
            min = 0.5f, max = 8f, step = 0.5f,
            defaultValue = VisionSettingsKeys.DEFAULT_DRAW_STROKE_WIDTH,
            unit = "px",
            summary = "绘制线条的粗细",
        ))
    }

    // ==================== 玩家与绿瓶分区 ====================

    /**
     * 构建"玩家与绿瓶"分区参数。
     *
     * 包含：玩家宽高比例、绿瓶上下边界比例。
     */
    private fun buildPlayerBottleParams() {
        paramDefs.add(ParamDef.Spacer(8))
        paramDefs.add(ParamDef.SeekBarFloat(
            label = context.getString(R.string.settings_player_width_ratio),
            key = "playerWidthRatio",
            min = 0.01f, max = 0.99f, step = 0.01f,
            defaultValue = VisionSettingsKeys.DEFAULT_PLAYER_WIDTH_RATIO,
            unit = "",
            summary = "玩家宽度占屏幕宽度的比例",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarFloat(
            label = context.getString(R.string.settings_player_height_ratio),
            key = "playerHeightRatio",
            min = 0.01f, max = 0.99f, step = 0.01f,
            defaultValue = VisionSettingsKeys.DEFAULT_PLAYER_HEIGHT_RATIO,
            unit = "",
            summary = "玩家高度占屏幕高度的比例",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarFloat(
            label = context.getString(R.string.settings_green_bottle_top),
            key = "greenBottleTop",
            min = 0f, max = 1f, step = 0.05f,
            defaultValue = VisionSettingsKeys.DEFAULT_GREEN_BOTTLE_TOP_RATIO,
            unit = "",
            summary = "绿瓶检测上边界占扫描线的比例",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarFloat(
            label = context.getString(R.string.settings_green_bottle_bottom),
            key = "greenBottleBottom",
            min = 0f, max = 1f, step = 0.05f,
            defaultValue = VisionSettingsKeys.DEFAULT_GREEN_BOTTLE_BOTTOM_RATIO,
            unit = "",
            summary = "绿瓶检测下边界占扫描线的比例",
        ))
    }

    // ==================== 检测参数分区 ====================

    /**
     * 构建"检测参数"分区参数。
     *
     * 包含：RGB 阈值（上下限）、最小段宽、缺口合并、padding、置信度。
     */
    private fun buildDetectionParams() {
        paramDefs.add(ParamDef.Spacer(8))
        paramDefs.add(ParamDef.RGBThreshold(
            label = context.getString(R.string.settings_rgb_min),
            rKey = "rgbRMin", gKey = "rgbGMin", bKey = "rgbBMin",
            defaultValueR = VisionSettingsKeys.DEFAULT_RGB_R_MIN,
            defaultValueG = VisionSettingsKeys.DEFAULT_RGB_G_MIN,
            defaultValueB = VisionSettingsKeys.DEFAULT_RGB_B_MIN,
            summary = "RGB 颜色检测下限（0~255）",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.RGBThreshold(
            label = context.getString(R.string.settings_rgb_max),
            rKey = "rgbRMax", gKey = "rgbGMax", bKey = "rgbBMax",
            defaultValueR = VisionSettingsKeys.DEFAULT_RGB_R_MAX,
            defaultValueG = VisionSettingsKeys.DEFAULT_RGB_G_MAX,
            defaultValueB = VisionSettingsKeys.DEFAULT_RGB_B_MAX,
            summary = "RGB 颜色检测上限（0~255）",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarInt(
            label = context.getString(R.string.settings_min_segment_width),
            key = "minSegmentWidth",
            min = 1, max = 50, step = 1,
            defaultValue = VisionSettingsKeys.DEFAULT_MIN_SEGMENT_WIDTH,
            unit = "px",
            summary = "检测片段的最小宽度，低于此值的片段将被忽略",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarInt(
            label = context.getString(R.string.settings_gap_merge),
            key = "gapMergeWidth",
            min = 0, max = 100, step = 1,
            defaultValue = VisionSettingsKeys.DEFAULT_GAP_MERGE_WIDTH,
            unit = "px",
            summary = "相邻片段之间的最大缺口距离，小于此值将被合并",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarInt(
            label = context.getString(R.string.settings_detection_padding),
            key = "detectionPadding",
            min = 0, max = 200, step = 5,
            defaultValue = VisionSettingsKeys.DEFAULT_DETECTION_PADDING,
            unit = "px",
            summary = "检测区域向内收缩的像素数",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarFloat(
            label = context.getString(R.string.settings_confidence_threshold),
            key = "confidenceThreshold",
            min = 0f, max = 1f, step = 0.05f,
            defaultValue = VisionSettingsKeys.DEFAULT_CONFIDENCE_THRESHOLD,
            unit = "",
            formatPercent = true,
            summary = "检测结果的可信度阈值，低于此值视为无效检测",
        ))
    }

    // ==================== 调试选项分区 ====================

    /**
     * 构建"调试选项"分区参数。
     *
     * 包含：日志开关、调试模式。
     */
    private fun buildDebugParams() {
        paramDefs.add(ParamDef.Spacer(8))
        paramDefs.add(ParamDef.Label(context.getString(R.string.settings_log_enabled)))
        paramDefs.add(ParamDef.Switch(
            key = "logEnabled",
            defaultValue = VisionSettingsKeys.DEFAULT_LOG_ENABLED,
            summary = "在 Logcat 中输出识别过程日志",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.Label(context.getString(R.string.settings_debug_mode)))
        paramDefs.add(ParamDef.Switch(
            key = "debugMode",
            defaultValue = VisionSettingsKeys.DEFAULT_DEBUG_MODE,
            summary = "启用调试模式：绘制额外调试信息（边界框、中心线等）",
        ))

        paramDefs.add(ParamDef.Spacer(12))
        paramDefs.add(ParamDef.SeekBarInt(
            label = context.getString(R.string.settings_log_buffer_capacity),
            key = "logBufferCapacity",
            min = VisionSettingsKeys.MIN_LOG_BUFFER_CAPACITY,
            max = VisionSettingsKeys.MAX_LOG_BUFFER_CAPACITY,
            step = 500,
            defaultValue = VisionSettingsKeys.DEFAULT_LOG_BUFFER_CAPACITY,
            unit = "条",
            summary = "内存日志缓冲区容量，超出后自动丢弃最旧日志",
        ))

        paramDefs.add(ParamDef.Spacer(24))
        paramDefs.add(ParamDef.Note(context.getString(R.string.settings_debug_note)))
    }
}
