// 火崽崽助手（HZZS）JNI JSON 解析器。
//
// 职责：
// - 将 C++ 返回的 JSON 字符串解析为 [FrameAnalysisResult]
//
// 不负责：
// - 不处理库加载（由 NativeLibraryLoader 处理）
// - 不处理 JNI 调用（由 NativeEngineFacade 处理）
//
// 设计原因：
// - 不使用第三方 JSON 库（如 Gson/Moshi），减少 APK 体积
// - 使用正则表达式从 JSON 字符串中提取字段值
// - 每个 extractXxx 函数独立搜索，不依赖字段顺序
// - 解析失败时返回默认值（0/0.0/false），而非抛异常

package top.azek431.hzzs.data.native

import top.azek431.hzzs.model.FrameAnalysisResult

/**
 * JNI JSON 解析器。
 *
 * 将 C++ 分析引擎返回的 JSON 字符串解析为 FrameAnalysisResult 对象。
 * 所有字段解析使用独立正则搜索，不依赖字段顺序。
 *
 * JSON 格式说明：
 * C++ 端使用 std::ostringstream 手动拼接 JSON，字段包括：
 * - scene_mode: Int, scene_confidence: Float
 * - runner_pose: Int, runner_grounded: Boolean, jump_stage: Int
 * - prompt_action: Int, prompt_target: Int, prompt_eta_ms: Float, prompt_confidence: Float
 * - hazards_count: Int, collectibles_count: Int
 *
 * 容错策略：
 * - 正则匹配失败 → 返回默认值（Int=0, Float=0.0, Boolean=false）
 * - 字段值类型不匹配 → 返回默认值
 * - 不验证 JSON 完整性，缺失字段静默使用默认值
 */
object NativeJsonParser {

    /**
     * 将 C++ 返回的 JSON 字符串解析为 [FrameAnalysisResult]。
     *
     * 解析流程：
     * 1. 定义三个内部辅助函数（extractFloat/extractInt/extractBoolean）
     * 2. 每个辅助函数使用正则表达式搜索对应字段
     * 3. 将匹配到的字符串转换为对应类型，失败则返回默认值
     * 4. 用提取的值构造 FrameAnalysisResult 对象
     *
     * @param json C++ 引擎返回的 JSON 字符串
     * @return 解析后的 FrameAnalysisResult
     */
    fun parse(json: String): FrameAnalysisResult {
        /**
         * 从 JSON 中提取 Float 类型字段值。
         *
         * 正则模式：匹配 "key": 后面的浮点数（支持科学计数法）
         * 示例：'"scene_confidence":0.98' → 0.98f
         *
         * @param key JSON 字段名
         * @return 解析后的 Float 值，匹配失败或类型错误返回 0f
         */
        fun extractFloat(key: String): Float {
            val pattern = "\"$key\":([-0-9.eE+]+)"
            val match = pattern.toRegex().find(json)
            return match?.groups?.get(1)?.value?.toFloatOrNull() ?: 0f
        }

        /**
         * 从 JSON 中提取 Int 类型字段值。
         *
         * 正则模式：匹配 "key": 后面的整数（支持负数）
         * 示例：'"scene_mode":3' → 3
         *
         * @param key JSON 字段名
         * @return 解析后的 Int 值，匹配失败或类型错误返回 0
         */
        fun extractInt(key: String): Int {
            val pattern = "\"$key\":(-?[0-9]+)"
            val match = pattern.toRegex().find(json)
            return match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
        }

        /**
         * 从 JSON 中提取 Boolean 类型字段值。
         *
         * 正则模式：匹配 "key": 后面的 true/false
         * 示例：'"runner_grounded":true' → true
         *
         * @param key JSON 字段名
         * @return 解析后的 Boolean 值，匹配失败返回 false
         */
        fun extractBoolean(key: String): Boolean {
            val pattern = "\"$key\":(true|false)"
            val match = pattern.toRegex().find(json)
            return match?.groups?.get(1)?.value == "true"
        }

        // 使用提取的值构造分析结果对象
        return FrameAnalysisResult(
            sceneMode = extractInt("scene_mode"),
            sceneConfidence = extractFloat("scene_confidence"),
            runnerPose = extractInt("runner_pose"),
            runnerGrounded = extractBoolean("runner_grounded"),
            jumpStage = extractInt("jump_stage"),
            promptAction = extractInt("prompt_action"),
            promptTarget = extractInt("prompt_target"),
            promptEtaMs = extractFloat("prompt_eta_ms"),
            promptConfidence = extractFloat("prompt_confidence"),
            hazardsCount = extractInt("hazards_count"),
            collectiblesCount = extractInt("collectibles_count"),
        )
    }
}
