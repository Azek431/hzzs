// 火崽崽助手（HZZS）视觉识别桥接层。
//
// 职责：
// - 独立于主 NativeAnalysisBridge 的视觉识别 JNI 入口
// - 暴露绿瓶单行扫描检测的 Kotlin 接口
// - 将像素数组 + 玩家坐标传入 C++ 检测器，解析 JSON 结果
//
// 设计原因：
// - 主 NativeAnalysisBridge 负责通用分析引擎（场景/姿态/ETA/提示）
// - 视觉识别模块（绿瓶检测等）需要独立演进，不污染主逻辑
// - 使用独立的 data/vision/ 包名，与 data/native/ 明确区分
// - 库加载共享 hzzs_native.so，无需额外 SO 文件

package top.azek431.hzzs.data.vision

import top.azek431.hzzs.core.data.native.NativeLibraryLoader
import top.azek431.hzzs.core.model.RectF

/**
 * 视觉识别桥接层。
 *
 * 独立于 NativeAnalysisBridge 的 JNI 入口，负责将视觉识别算法
 *（如绿瓶单行扫描）的调用封装为 Kotlin 接口。
 *
 * 当前唯一功能：
 * - scanGreenBottle()：在原始像素上执行绿瓶检测
 *
 * 调用流程：
 * 1. 检查 NativeLibraryLoader.isAvailable，失败则返回 null
 * 2. 调用 nativeVisionScanGreenBottle() JNI 方法
 * 3. 解析 JSON 结果，返回 VisionGreenBottleResult
 *
 * @see NativeLibraryLoader 库加载器（共享 hzzs_native.so）
 * @see top.azek431.hzzs.NativeAnalysisBridge 主分析引擎桥接
 */
object VisionAnalysisBridge {

    /**
     * 在原始像素数组上执行绿瓶单行扫描检测。
     *
     * 算法：
     * 1. 在玩家中心 Y 水平线上，从玩家右侧开始向右扫描
     * 2. 使用 RGB 色差条件判断绿色像素
     * 3. 聚合连续绿色像素为片段，合并相邻片段
     * 4. 输出归一化坐标、置信度、成本等信息
     *
     * @param pixels ARGB 像素数组（每元素 0xAARRGGBB），长度为 width * height
     * @param width 屏幕宽度（像素）
     * @param height 屏幕高度（像素）
     * @param playerBounds 玩家矩形归一化坐标
     * @return 绿瓶检测结果，库不可用时返回 null
     */
    fun scanGreenBottle(
        pixels: IntArray,
        width: Int,
        height: Int,
        playerBounds: RectF,
    ): VisionGreenBottleResult? {
        // 库不可用时直接返回 null，不调用 JNI
        if (!NativeLibraryLoader.isAvailable) return null

        // 参数校验
        if (pixels.size != width * height) {
            android.util.Log.w(
                "HZZS-Vision",
                "pixels size mismatch: ${pixels.size} != ${width}x${height}"
            )
            return null
        }

        if (!playerBounds.isValid()) {
            android.util.Log.w(
                "HZZS-Vision",
                "Invalid player bounds: $playerBounds"
            )
            return null
        }

        // 调用 JNI 原生方法
        val json = nativeVisionScanGreenBottle(
            pixels,
            width,
            height,
            playerBounds.left,
            playerBounds.top,
            playerBounds.right,
            playerBounds.bottom,
        )

        // 解析 JSON 结果
        return parseGreenBottleResult(json)
    }

    // ==================== JNI 原生方法声明 ====================

    /**
     * 绿瓶单行扫描 JNI 调用。
     *
     * 对应 C++ 端的 Java_top_azek431_hzzs_data_vision_VisionAnalysisBridge_nativeVisionScanGreenBottle。
     * 传入原始 ARGB 像素数组和玩家归一化坐标，返回 JSON 格式检测结果。
     */
    private external fun nativeVisionScanGreenBottle(
        pixels: IntArray,
        width: Int,
        height: Int,
        playerLeft: Float,
        playerTop: Float,
        playerRight: Float,
        playerBottom: Float,
    ): String

    // ==================== JSON 解析 ====================

    /**
     * 将 C++ 返回的 JSON 字符串解析为 VisionGreenBottleResult。
     *
     * 使用正则表达式提取字段，与 NativeJsonParser 风格一致。
     * 解析失败时返回默认值，而非抛异常。
     *
     * @param json C++ 检测器返回的 JSON 字符串
     * @return 解析后的检测结果
     */
    private fun parseGreenBottleResult(json: String): VisionGreenBottleResult {
        fun extractFloat(key: String): Float {
            val pattern = "\"$key\":([-0-9.eE+]+)"
            val match = pattern.toRegex().find(json)
            return match?.groups?.get(1)?.value?.toFloatOrNull() ?: 0f
        }

        fun extractInt(key: String): Int {
            val pattern = "\"$key\":(-?[0-9]+)"
            val match = pattern.toRegex().find(json)
            return match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
        }

        fun extractBoolean(key: String): Boolean {
            val pattern = "\"$key\":(true|false)"
            val match = pattern.toRegex().find(json)
            return match?.groups?.get(1)?.value == "true"
        }

        val found = extractBoolean("found")

        // 如果未检测到，直接返回默认结果
        if (!found) {
            val errorMsg = json.substringAfter("\"error\":\"").substringBefore("\"")
            if (errorMsg.isNotEmpty() && errorMsg != json) {
                android.util.Log.w("HZZS-Vision", "Vision error: $errorMsg")
            }
            return VisionGreenBottleResult(found = false)
        }

        return VisionGreenBottleResult(
            found = true,
            scanY = extractInt("scanY"),
            leftX = extractInt("leftX"),
            rightX = extractInt("rightX"),
            centerX = extractInt("centerX"),
            edgeGapPx = extractInt("edgeGapPx"),
            centerDistancePx = extractInt("centerDistancePx"),
            leftRatio = extractFloat("leftRatio"),
            rightRatio = extractFloat("rightRatio"),
            centerXRatio = extractFloat("centerXRatio"),
            edgeGapRatio = extractFloat("edgeGapRatio"),
            costMs = extractFloat("costMs"),
            confidence = extractFloat("confidence"),
            rawSegmentCount = extractInt("rawSegmentCount"),
            mergedSegmentCount = extractInt("mergedSegmentCount"),
        )
    }
}

/**
 * 绿瓶单行扫描检测结果。
 *
 * 由 VisionAnalysisBridge.scanGreenBottle() 解析 C++ 返回的 JSON 得到。
 * 所有坐标均为归一化坐标（0.0 ~ 1.0），像素坐标独立提供。
 *
 * @property found 是否检测到绿瓶
 * @property scanY 扫描线 Y 坐标（像素）
 * @property leftX 绿瓶左边界 X（像素）
 * @property rightX 绿瓶右边界 X（像素）
 * @property centerX 绿瓶中心 X（像素）
 * @property edgeGapPx 左边缘到玩家右侧的距离（像素）
 * @property centerDistancePx 中心到玩家右侧的距离（像素）
 * @property leftRatio 左边界归一化坐标
 * @property rightRatio 右边界归一化坐标
 * @property centerXRatio 中心归一化坐标
 * @property edgeGapRatio 边缘距离归一化
 * @property costMs 扫描耗时（毫秒）
 * @property confidence 检测置信度（0.0 ~ 1.0）
 * @property rawSegmentCount 原始绿色片段数量（合并前）
 * @property mergedSegmentCount 合并后绿色片段数量
 */
data class VisionGreenBottleResult(
    val found: Boolean = false,
    val scanY: Int = 0,
    val leftX: Int = 0,
    val rightX: Int = 0,
    val centerX: Int = 0,
    val edgeGapPx: Int = 0,
    val centerDistancePx: Int = 0,
    val leftRatio: Float = 0f,
    val rightRatio: Float = 0f,
    val centerXRatio: Float = 0f,
    val edgeGapRatio: Float = 0f,
    val costMs: Float = 0f,
    val confidence: Float = 0f,
    val rawSegmentCount: Int = 0,
    val mergedSegmentCount: Int = 0,
) {
    /** 是否有有效的绿瓶检测结果 */
    val isValid: Boolean get() = found && confidence > 0f

    /** 置信度可读文本 */
    val confidenceText: String
        get() = if (found) String.format("%.1f%%", confidence * 100f) else "--"

    /** 边缘距离可读文本 */
    val edgeGapText: String
        get() = if (found && edgeGapPx > 0) "$edgeGapPx px" else "--"
}
