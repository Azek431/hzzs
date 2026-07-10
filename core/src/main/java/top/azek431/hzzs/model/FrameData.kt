// 火崽崽助手（HZZS）数据模型。
//
// 包含所有与帧分析相关的数据类：
// - RectF：归一化矩形坐标（0.0 ~ 1.0），用于描述玩家边界、危险物边界等
// - FrameAnalysisResult：单帧分析结果，由 C++ 引擎返回的 JSON 解析得到
// - HazardDetail：危险物详细信息
// - DetectedObject：检测到的游戏对象（玩家、危险物、收藏物统一表示）
// - ActionPrompt：结构化动作提示
//
// 注意：所有枚举常量和便捷属性定义在 FrameAnalysisResult 内部 companion object 中，
// 方便从单一位置维护常量值，避免魔法数字散落各处。

package top.azek431.hzzs.core.model

// ==================== 归一化矩形坐标 ====================

/**
 * 归一化矩形坐标（0.0 ~ 1.0），与设备分辨率无关。
 *
 * 用于描述玩家边界、危险物边界、游戏区域适配等。
 * 所有坐标在 0.0 ~ 1.0 范围内，可自动适配不同屏幕尺寸。
 */
data class RectF(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    /** 矩形宽度 */
    val width: Float get() = right - left

    /** 矩形高度 */
    val height: Float get() = bottom - top

    /** 中心点 X */
    val centerX: Float get() = (left + right) * 0.5f

    /** 中心点 Y */
    val centerY: Float get() = (top + bottom) * 0.5f

    /** 是否有效（right > left 且 bottom > top） */
    fun isValid(): Boolean = right > left && bottom > top

    /**
     * 膨胀矩形（向四周扩展指定比例）。
     *
     * 用于碰撞检测时的安全余量扩展——将矩形向外扩展 factor 比例，
     * 使碰撞检测更宽容，避免因像素级精度问题导致的漏检。
     *
     * @param factor 扩展比例（如 0.1 表示向外扩展 10%）
     * @return 膨胀后的新 RectF 实例
     */
    fun expand(factor: Float): RectF = RectF(
        left - left * factor, top - top * factor,
        right + (1f - right) * factor, bottom + (1f - bottom) * factor
    )

    /**
     * 与另一个矩形的交集，无交集时返回 null。
     *
     * 用于计算两个矩形重叠区域的精确边界。
     * 例如：判断玩家是否已经进入危险物的碰撞区域。
     *
     * @param other 待求交集的另一个矩形
     * @return 交集矩形，或 null（无重叠）
     */
    fun intersection(other: RectF): RectF? {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (r > l && b > t) RectF(l, t, r, b) else null
    }

    /** 判断点是否在矩形内 */
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}

// ==================== 单帧分析结果 ====================

/**
 * 单帧分析结果数据类。
 *
 * 由 NativeAnalysisBridge.analyzeFrame() 解析 C++ 返回的 JSON 字符串得到。
 * 所有字段直接映射 C++ 端的 AnalysisResult 结构体。
 */
data class FrameAnalysisResult(
    // === 场景信息 ===
    val sceneMode: Int,                // SceneMode 枚举值
    val sceneConfidence: Float,        // 场景可信度（0.0 ~ 1.0）

    // === 角色运动状态 ===
    val runnerPose: Int,               // RunnerPose 枚举值
    val runnerGrounded: Boolean,       // 是否着地

    // === 跳跃阶段 ===
    val jumpStage: Int,                // 0=地面, 1=首跳, 2=二连跳

    // === HUD 动作提示 ===
    val promptAction: Int,             // PromptAction 枚举值（0=无, 1=跳跃, 2=二连跳, 3=滑铲）
    val promptTarget: Int,             // GameObjectType 枚举值
    val promptEtaMs: Float,            // 到达时间（毫秒），-1.0 表示不可用
    val promptConfidence: Float,       // 提示可信度（0.0 ~ 1.0）

    // === 检测结果统计 ===
    val hazardsCount: Int,             // 检测到危险物数量
    val collectiblesCount: Int,        // 检测到可收集物品数量

    // === 新增：危险物详细信息列表 ===
    val hazardDetails: List<HazardDetail> = emptyList(),

    // === 绘制数据：玩家矩形（归一化坐标） ===
    val playerBounds: RectF? = null,

    // === 绘制数据：危险物矩形列表（归一化坐标） ===
    val hazardBounds: List<RectF> = emptyList(),
) {
    companion object {
        // ==================== 场景模式常量 ====================
        const val SCENE_UNKNOWN = 0
        const val SCENE_MENU = 1
        const val SCENE_COUNTDOWN = 2
        const val SCENE_GROUND_RUN = 3
        const val SCENE_FLIGHT_RUN = 4
        const val SCENE_RESULT = 5
        const val SCENE_OCCLUDED = 6

        // ==================== 角色姿态常量 ====================
        const val POSE_UNKNOWN = 0
        const val POSE_RUN = 1
        const val POSE_JUMP_UP = 2
        const val POSE_JUMP_TOP = 3
        const val POSE_JUMP_DOWN = 4
        const val POSE_SLIDE = 5
        const val POSE_FLIGHT = 6

        // ==================== 提示动作常量 ====================
        const val PROMPT_NONE = 0
        const val PROMPT_JUMP = 1
        const val PROMPT_JUMP_AGAIN = 2
        const val PROMPT_SLIDE = 3
    }

    // ==================== 便捷属性 ====================

    /** 当前是否为地面跑酷场景 */
    val isGroundRun: Boolean get() = sceneMode == SCENE_GROUND_RUN

    /** 是否有有效的动作提示 */
    val hasPrompt: Boolean get() = promptAction != PROMPT_NONE

    /** 动作提示文本（中文） */
    val promptText: String
        get() = when (promptAction) {
            PROMPT_JUMP -> "⬆ 跳跃"
            PROMPT_JUMP_AGAIN -> "⬆⬆ 二连跳"
            PROMPT_SLIDE -> "⬇ 滑铲"
            else -> ""
        }

    /** 危险物类型文本（中文） */
    val promptTargetText: String
        get() = when (promptTarget) {
            1 -> "蛋糕断层"
            2 -> "毒瓶"
            3 -> "裱花袋"
            else -> "未知"
        }

    /** ETA 可读文本 */
    val etaText: String
        get() = if (promptEtaMs > 0f && promptEtaMs < 9999f) {
            "${promptEtaMs.toInt()}ms"
        } else {
            "--"
        }

    /** 场景模式可读文本 */
    val sceneText: String
        get() = when (sceneMode) {
            SCENE_UNKNOWN -> "场景未知"
            SCENE_MENU -> "菜单"
            SCENE_COUNTDOWN -> "倒计时"
            SCENE_GROUND_RUN -> "地面跑酷"
            SCENE_FLIGHT_RUN -> "飞行模式"
            SCENE_RESULT -> "结算"
            SCENE_OCCLUDED -> "已遮挡"
            else -> "未知"
        }

    /** 角色姿态可读文本 */
    val poseText: String
        get() = when (runnerPose) {
            POSE_UNKNOWN -> "姿态未知"
            POSE_RUN -> "奔跑"
            POSE_JUMP_UP -> "起跳上升"
            POSE_JUMP_TOP -> "滞空顶点"
            POSE_JUMP_DOWN -> "下落"
            POSE_SLIDE -> "滑铲"
            POSE_FLIGHT -> "飞行"
            else -> "未知"
        }
}

/** 危险物详细信息 */
data class HazardDetail(
    val type: Int,                // GameObjectType 枚举值
    val etaMs: Float,             // 到达时间（毫秒）
    val confidence: Float,        // 可信度
    val preferredAction: Int,     // 推荐动作
    val requiredJumpStage: Int,   // 所需跳跃阶段
    val bounds: RectF? = null,    // 边界
)

// ==================== 检测对象与动作提示 ====================

/**
 * 检测到的游戏对象（玩家、危险物、收藏物统一表示）。
 *
 * 用于替代分散的 playerBounds/hazardBounds 参数传递，
 * 将所有检测到的游戏对象统一为一个数据结构。
 *
 * @property type 对象类型（玩家/地面危险物/顶部危险物/收藏物/平台）
 * @property bounds 归一化边界坐标（0.0 ~ 1.0）
 * @property confidence 检测可信度（0.0 ~ 1.0）
 * @property velocityX X 方向速度（归一化坐标/秒），向左滚动时为负数
 * @property velocityY Y 方向速度（归一化坐标/秒），向上运动时为负数
 * @property trackId 对象追踪 ID，用于跨帧跟踪同一对象
 */
data class DetectedObject(
    val type: ObjectType,
    val bounds: RectF,
    val confidence: Float,
    val velocityX: Float = 0f,
    val velocityY: Float = 0f,
    val trackId: Int = 0,
) {
    /** 检测对象类型枚举 */
    enum class ObjectType {
        /** 未知类型，无法分类的对象 */
        UNKNOWN,
        /** 玩家角色 */
        PLAYER,
        /** 地面危险物（如蛋糕断层） */
        HAZARD_GROUND,
        /** 顶部危险物（如悬垂裱花袋） */
        HAZARD_TOP,
        /** 可收集物品（如糖果、护盾） */
        COLLECTIBLE,
        /** 平台/地面 */
        PLATFORM,
    }
}

/**
 * 结构化动作提示。
 *
 * 由 ActionPromptEngine 输出，供 HUD 渲染器显示给用户。
 * 包含动作类型、目标对象、ETA 和可信度。
 *
 * @property action 动作类型（PROMPT_NONE/JUMP/JUMP_AGAIN/SLIDE）
 * @property target 目标对象类型（GameObjectType 枚举值）
 * @property etaMs 预计到达时间（毫秒），-1.0 表示不可用
 * @property confidence 可信度（0.0 ~ 1.0）
 */
data class ActionPrompt(
    val action: Int,
    val target: Int,
    val etaMs: Float,
    val confidence: Float,
)
