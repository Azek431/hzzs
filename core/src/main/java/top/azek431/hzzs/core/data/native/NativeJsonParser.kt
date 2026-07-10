// 火崽崽助手（HZZS）JNI JSON 解析器。
//
// 职责：
// - 将 C++ 返回的 JSON 字符串解析为 [FrameAnalysisResult]
// - 解析 HUD 绘制数据（玩家矩形、危险物矩形等归一化坐标）
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

package top.azek431.hzzs.core.data.native

import top.azek431.hzzs.core.model.FrameAnalysisResult
import top.azek431.hzzs.core.model.HazardDetail
import top.azek431.hzzs.core.model.RectF

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
 * - hazards[]: 每个包含 type, eta_ms, confidence, action, required_jump_stage
 * - player: { l, t, r, b } 玩家归一化矩形
 * - hazard_bounds[]: 危险物归一化矩形列表
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
     * 4. 解析 hazards 数组（含详细信息）
     * 5. 解析绘制数据（玩家矩形、危险物矩形）
     * 6. 用提取的值构造 FrameAnalysisResult 对象
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

        /**
         * 从 JSON 中提取归一化矩形坐标。
         *
         * 搜索模式：匹配 "key":{"l":X,"t":Y,"r":X2,"b":Y2} 结构
         * 示例：'"player":{"l":0.14,"t":0.66,"r":0.24,"b":0.84}'
         *
         * @param key JSON 字段名
         * @return 解析后的 RectF，匹配失败返回 null
         */
        fun extractRect(key: String): RectF? {
            // 匹配 "key":{"l":...,"t":...,"r":...,"b":...}
            val pattern = "\"$key\":\\{\"l\":([-0-9.eE+]+),\"t\":([-0-9.eE+]+),\"r\":([-0-9.eE+]+),\"b\":([-0-9.eE+]+)\\}"
            val match = pattern.toRegex().find(json)
            return match?.let { m ->
                val l = m.groupValues[1].toFloatOrNull() ?: return@let null
                val t = m.groupValues[2].toFloatOrNull() ?: return@let null
                val r = m.groupValues[3].toFloatOrNull() ?: return@let null
                val b = m.groupValues[4].toFloatOrNull() ?: return@let null
                RectF(l, t, r, b)
            }
        }

        /**
         * 从 JSON 中提取 hazards 数组，解析为 HazardDetail 列表。
         *
         * 每个 hazard 对象包含：type, eta_ms, confidence, action, required_jump_stage, bounds
         *
         * @return HazardDetail 列表
         */
        fun extractHazards(): List<HazardDetail> {
            // 提取 hazards 数组内容（简单处理：找到第一个 "[" 和匹配的 "]"）
            val hazardsMatch = Regex("\"hazards\":\\[").find(json) ?: return emptyList()
            val startIndex = hazardsMatch.range.start + "\"hazards\":[".length
            var bracketCount = 1
            var i = startIndex
            while (i < json.length && bracketCount > 0) {
                when (json[i]) {
                    '[' -> bracketCount++
                    ']' -> bracketCount--
                }
                i++
            }
            val hazardsContent = json.substring(startIndex, i - 1)

            // 按 },{ 分割各个 hazard 对象（处理嵌套 bounds 结构）
            val hazardObjects = hazardsContent.split("},{").map {
                if (it.startsWith('{')) it else "{${it}"
            }.filter { it.endsWith('}') }

            val hazards = mutableListOf<HazardDetail>()
            for (hazardStr in hazardObjects) {
                val type = Regex("\"type\":(-?[0-9]+)").find(hazardStr)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val etaMs = Regex("\"eta_ms\":([-0-9.eE+]+)").find(hazardStr)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                val confidence = Regex("\"confidence\":([-0-9.eE+]+)").find(hazardStr)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: Regex("\"conf\":([-0-9.eE+]+)").find(hazardStr)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                val action = Regex("\"action\":(-?[0-9]+)").find(hazardStr)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val jumpStage = Regex("\"jump_stage\":(-?[0-9]+)").find(hazardStr)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("\"required_jump_stage\":(-?[0-9]+)").find(hazardStr)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                hazards.add(HazardDetail(type, etaMs, confidence, action, jumpStage))
            }
            return hazards
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
            hazardDetails = extractHazards(),
            // 绘制数据：玩家矩形
            playerBounds = extractRect("player"),
            // 绘制数据：危险物矩形列表（从 hazards 数组中提取 bounds）
            hazardBounds = extractHazardBounds(json),
        )
    }

    /**
     * 从 JSON 中提取危险物矩形列表。
     *
     * 从 hazards 数组的每个对象中提取 "bounds" 字段。
     *
     * @return RectF 列表
     */
    private fun extractHazardBounds(json: String): List<RectF> {
        val result = mutableListOf<RectF>()
        val hazardsOpen = Regex("\"hazards\":\\[").find(json) ?: return emptyList()
        val startIdx = hazardsOpen.range.endInclusive + 1

        // 逐个解析 hazards 数组中的对象
        var depth = 1
        var i = startIdx
        while (i < json.length && depth > 0) {
            when (json[i]) {
                '[' -> depth++
                ']' -> depth--
            }
            i++
        }
        val hazardsBody = json.substring(startIdx, i - 1)

        // 按 },{ 分割各个 hazard 对象
        val hazardObjects = hazardsBody.split("},{").map {
            if (it.startsWith('{')) it else "{${it}"
        }.filter { it.endsWith('}') }

        for (hazardStr in hazardObjects) {
            // 在每个 hazard 对象中查找 bounds
            val boundsIdx = hazardStr.indexOf("\"bounds\":{")
            if (boundsIdx >= 0) {
                val boundsStart = boundsIdx + 11 // "\"bounds\":{".length
                var bd = 1
                var bi = boundsStart
                while (bi < hazardStr.length && bd > 0) {
                    when (hazardStr[bi]) {
                        '{' -> bd++
                        '}' -> bd--
                    }
                    bi++
                }
                val boundsContent = hazardStr.substring(boundsStart, bi - 1)
                val l = Regex("\"l\":([-0-9.eE+]+)").find(boundsContent)?.groupValues?.get(1)?.toFloatOrNull()
                val t = Regex("\"t\":([-0-9.eE+]+)").find(boundsContent)?.groupValues?.get(1)?.toFloatOrNull()
                val r = Regex("\"r\":([-0-9.eE+]+)").find(boundsContent)?.groupValues?.get(1)?.toFloatOrNull()
                val bb = Regex("\"b\":([-0-9.eE+]+)").find(boundsContent)?.groupValues?.get(1)?.toFloatOrNull()
                if (l != null && t != null && r != null && bb != null) {
                    result.add(RectF(l, t, r, bb))
                }
            }
        }
        return result
    }
}
