// 火崽崽助手（HZZS）视觉识别设置参数键名与默认值。
//
// 所有视觉识别与绘制参数的 SharedPreferences 键名、默认值、取值范围集中定义在此。
// 使用方式：
//   val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
//   val threshold = prefs.getFloat(KEY_CONFIDENCE_THRESHOLD, DEFAULT_CONFIDENCE_THRESHOLD)
//
// 设计原因：
// - 与 FeatureFlags 分离，视觉识别参数属于不同功能域
// - 默认值和范围集中定义，避免魔法数字散落在各层
// - 键名统一前缀 VISION_，避免与其他 SharedPreferences 冲突

package top.azek431.hzzs.ui.settings

import android.content.Context

object VisionSettingsKeys {

    // === SharedPreferences 文件名 ===
    const val PREFS_NAME = "hzzs_vision_settings"

    // ==================== 识别模式与截图 ====================

    /** 识别模式：0=离线模拟（默认），1=截图分析，2=实时流 */
    const val KEY_DETECT_MODE = "vision_detect_mode"
    const val DEFAULT_DETECT_MODE = 0
    const val MIN_DETECT_MODE = 0
    const val MAX_DETECT_MODE = 2

    /** 截图方式：0=无障碍takeScreenshot（默认），1=MediaProjection，2=截屏服务 */
    const val KEY_SCREENSHOT_METHOD = "vision_screenshot_method"
    const val DEFAULT_SCREENSHOT_METHOD = 0
    const val MIN_SCREENSHOT_METHOD = 0
    const val MAX_SCREENSHOT_METHOD = 2

    // ==================== 时序控制 ====================

    /** 循环识别间隔（毫秒），默认 100ms，范围 20~2000 */
    const val KEY_LOOP_INTERVAL_MS = "vision_loop_interval_ms"
    const val DEFAULT_LOOP_INTERVAL_MS = 100
    const val MIN_LOOP_INTERVAL_MS = 20
    const val MAX_LOOP_INTERVAL_MS = 2000

    /** 单次绘制停留时间（毫秒），默认 400ms，范围 50~5000 */
    const val KEY_DRAW_STAY_MS = "vision_draw_stay_ms"
    const val DEFAULT_DRAW_STAY_MS = 400
    const val MIN_DRAW_STAY_MS = 50
    const val MAX_DRAW_STAY_MS = 5000

    // ==================== HUD 显示开关 ====================

    /** 是否显示扫描线，默认 true */
    const val KEY_SHOW_SCAN_LINE = "vision_show_scan_line"
    const val DEFAULT_SHOW_SCAN_LINE = true

    /** 是否显示数值标签，默认 true */
    const val KEY_SHOW_VALUE_LABEL = "vision_show_value_label"
    const val DEFAULT_SHOW_VALUE_LABEL = true

    /** 是否显示中心点，默认 true */
    const val KEY_SHOW_CENTER_POINT = "vision_show_center_point"
    const val DEFAULT_SHOW_CENTER_POINT = true

    // ==================== 绘制样式 ====================

    /** 绘制颜色（ARGB 整型），默认青色 #00BCD4 */
    const val KEY_DRAW_COLOR = "vision_draw_color"
    const val DEFAULT_DRAW_COLOR = 0xFF00BCD4.toInt()

    /** 绘制透明度（0.0~1.0），默认 0.85 */
    const val KEY_DRAW_ALPHA = "vision_draw_alpha"
    const val DEFAULT_DRAW_ALPHA = 0.85f
    const val MIN_ALPHA = 0.0f
    const val MAX_ALPHA = 1.0f

    /** 绘制线宽（像素），默认 2.0，范围 0.5~8.0 */
    const val KEY_DRAW_STROKE_WIDTH = "vision_draw_stroke_width"
    const val DEFAULT_DRAW_STROKE_WIDTH = 2.0f
    const val MIN_STROKE_WIDTH = 0.5f
    const val MAX_STROKE_WIDTH = 8.0f

    // ==================== 玩家参考比例 ====================

    /** 玩家参考宽度比例（相对于屏幕宽度 0.0~1.0），默认 0.15 */
    const val KEY_PLAYER_WIDTH_RATIO = "vision_player_width_ratio"
    const val DEFAULT_PLAYER_WIDTH_RATIO = 0.15f
    const val MIN_PLAYER_RATIO = 0.01f
    const val MAX_PLAYER_RATIO = 0.99f

    /** 玩家参考高度比例（相对于屏幕高度 0.0~1.0），默认 0.20 */
    const val KEY_PLAYER_HEIGHT_RATIO = "vision_player_height_ratio"
    const val DEFAULT_PLAYER_HEIGHT_RATIO = 0.20f

    // ==================== 绿瓶检测参数 ====================

    /** 绿瓶上边界比例（相对于扫描线 0.0~1.0），默认 0.30 */
    const val KEY_GREEN_BOTTLE_TOP_RATIO = "vision_green_bottle_top_ratio"
    const val DEFAULT_GREEN_BOTTLE_TOP_RATIO = 0.30f
    const val MIN_RATIO = 0.0f
    const val MAX_RATIO = 1.0f

    /** 绿瓶下边界比例（相对于扫描线 0.0~1.0），默认 0.70 */
    const val KEY_GREEN_BOTTLE_BOTTOM_RATIO = "vision_green_bottle_bottom_ratio"
    const val DEFAULT_GREEN_BOTTLE_BOTTOM_RATIO = 0.70f

    // ==================== RGB 阈值 ====================

    /** R 通道下限，默认 30，范围 0~255 */
    const val KEY_RGB_R_MIN = "vision_rgb_r_min"
    const val DEFAULT_RGB_R_MIN = 30
    const val MIN_RGB = 0
    const val MAX_RGB = 255

    /** G 通道下限，默认 120，范围 0~255 */
    const val KEY_RGB_G_MIN = "vision_rgb_g_min"
    const val DEFAULT_RGB_G_MIN = 120

    /** B 通道下限，默认 30，范围 0~255 */
    const val KEY_RGB_B_MIN = "vision_rgb_b_min"
    const val DEFAULT_RGB_B_MIN = 30

    /** R 通道上限，默认 255，范围 0~255 */
    const val KEY_RGB_R_MAX = "vision_rgb_r_max"
    const val DEFAULT_RGB_R_MAX = 255

    /** G 通道上限，默认 255，范围 0~255 */
    const val KEY_RGB_G_MAX = "vision_rgb_g_max"
    const val DEFAULT_RGB_G_MAX = 255

    /** B 通道上限，默认 255，范围 0~255 */
    const val KEY_RGB_B_MAX = "vision_rgb_b_max"
    const val DEFAULT_RGB_B_MAX = 255

    // ==================== 形态学处理 ====================

    /** 最小段宽（像素），默认 3，范围 1~50 */
    const val KEY_MIN_SEGMENT_WIDTH = "vision_min_segment_width"
    const val DEFAULT_MIN_SEGMENT_WIDTH = 3

    /** 小缺口合并宽度（像素），默认 5，范围 0~100 */
    const val KEY_GAP_MERGE_WIDTH = "vision_gap_merge_width"
    const val DEFAULT_GAP_MERGE_WIDTH = 5

    /** 检测区域 padding（像素），默认 10，范围 0~200 */
    const val KEY_DETECTION_PADDING = "vision_detection_padding"
    const val DEFAULT_DETECTION_PADDING = 10

    // ==================== 置信度 ====================

    /** Confidence 阈值（0.0~1.0），默认 0.50 */
    const val KEY_CONFIDENCE_THRESHOLD = "vision_confidence_threshold"
    const val DEFAULT_CONFIDENCE_THRESHOLD = 0.50f

    // ==================== 调试选项 ====================

    /** 是否输出识别日志，默认 false */
    const val KEY_LOG_ENABLED = "vision_log_enabled"
    const val DEFAULT_LOG_ENABLED = false

    /** 是否启用调试模式（额外绘制调试信息），默认 false */
    const val KEY_DEBUG_MODE = "vision_debug_mode"
    const val DEFAULT_DEBUG_MODE = false

    // ==================== 工具方法 ====================

    private fun getPrefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 将所有参数重置为默认值 */
    fun resetAll(ctx: Context) {
        getPrefs(ctx).edit().clear().apply {
            putInt(KEY_DETECT_MODE, DEFAULT_DETECT_MODE)
            putInt(KEY_SCREENSHOT_METHOD, DEFAULT_SCREENSHOT_METHOD)
            putInt(KEY_LOOP_INTERVAL_MS, DEFAULT_LOOP_INTERVAL_MS)
            putInt(KEY_DRAW_STAY_MS, DEFAULT_DRAW_STAY_MS)
            putBoolean(KEY_SHOW_SCAN_LINE, DEFAULT_SHOW_SCAN_LINE)
            putBoolean(KEY_SHOW_VALUE_LABEL, DEFAULT_SHOW_VALUE_LABEL)
            putBoolean(KEY_SHOW_CENTER_POINT, DEFAULT_SHOW_CENTER_POINT)
            putInt(KEY_DRAW_COLOR, DEFAULT_DRAW_COLOR)
            putFloat(KEY_DRAW_ALPHA, DEFAULT_DRAW_ALPHA)
            putFloat(KEY_DRAW_STROKE_WIDTH, DEFAULT_DRAW_STROKE_WIDTH)
            putFloat(KEY_PLAYER_WIDTH_RATIO, DEFAULT_PLAYER_WIDTH_RATIO)
            putFloat(KEY_PLAYER_HEIGHT_RATIO, DEFAULT_PLAYER_HEIGHT_RATIO)
            putFloat(KEY_GREEN_BOTTLE_TOP_RATIO, DEFAULT_GREEN_BOTTLE_TOP_RATIO)
            putFloat(KEY_GREEN_BOTTLE_BOTTOM_RATIO, DEFAULT_GREEN_BOTTLE_BOTTOM_RATIO)
            putInt(KEY_RGB_R_MIN, DEFAULT_RGB_R_MIN)
            putInt(KEY_RGB_G_MIN, DEFAULT_RGB_G_MIN)
            putInt(KEY_RGB_B_MIN, DEFAULT_RGB_B_MIN)
            putInt(KEY_RGB_R_MAX, DEFAULT_RGB_R_MAX)
            putInt(KEY_RGB_G_MAX, DEFAULT_RGB_G_MAX)
            putInt(KEY_RGB_B_MAX, DEFAULT_RGB_B_MAX)
            putInt(KEY_MIN_SEGMENT_WIDTH, DEFAULT_MIN_SEGMENT_WIDTH)
            putInt(KEY_GAP_MERGE_WIDTH, DEFAULT_GAP_MERGE_WIDTH)
            putInt(KEY_DETECTION_PADDING, DEFAULT_DETECTION_PADDING)
            putFloat(KEY_CONFIDENCE_THRESHOLD, DEFAULT_CONFIDENCE_THRESHOLD)
            putBoolean(KEY_LOG_ENABLED, DEFAULT_LOG_ENABLED)
            putBoolean(KEY_DEBUG_MODE, DEFAULT_DEBUG_MODE)
        }.apply()
    }
}
