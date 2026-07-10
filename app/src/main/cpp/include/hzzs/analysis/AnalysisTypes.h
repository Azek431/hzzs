#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "hzzs/analysis/Geometry.h"

namespace hzzs::analysis {

/** 常量：表示 ETA（预计到达时间）不可用。使用 -1.0F 作为哨兵值，区别于合法的 ETA。 */
constexpr float kNoEtaMs = -1.0F;

/** 常量：最大跳跃段数。地面 0 段、首跳 1 段、二连跳 2 段。由游戏机制决定。 */
constexpr std::uint8_t kMaxJumpStage = 2;

/**
 * 场景模式枚举：描述当前游戏画面的全局状态。
 *
 * 由 SceneStateMachine 根据视觉层 hint 稳定化后输出。
 * 每种模式代表不同游戏阶段，引擎据此选择对应处理逻辑：
 * - kUnknown：场景不明，可能是画面突变或初始化未完成
 * - kMenu：主菜单/暂停菜单界面
 * - kCountdown：倒计时阶段（开始跑酷前的准备）
 * - kGroundRun：地面跑酷阶段（主要的分析目标）
 * - kFlightRun：飞行模式阶段（角色在空中飞行）
 * - kResult：结算界面（本局结束）
 * - kOccluded：外部遮挡（通知栏、来电等覆盖游戏画面）
 *
 * 注意：kOccluded 优先级最高——检测到遮挡直接返回，不进行任何分析。
 */
enum class SceneMode {
    kUnknown,
    kMenu,
    kCountdown,
    kGroundRun,
    kFlightRun,
    kResult,
    kOccluded,
};

/**
 * 角色姿态枚举：描述玩家在当前帧的运动状态。
 *
 * 由 RunnerStateMachine 根据玩家矩形边界和垂直速度推断：
 * - kUnknown：尚未初始化基线，或数据不可信
 * - kRun：在地面奔跑（角色底部接近基线）
 * - kJumpUp：起跳上升阶段（垂直速度 < -0.070）
 * - kJumpTop：滞空顶点（垂直速度接近 0）
 * - kJumpDown：下落阶段（垂直速度 > 0.070）
 * - kSlide：滑行/下滑姿态（角色高度显著降低，约基线的 76%）
 * - kFlight：飞行模式（仅当场景模式为 kFlightRun 时设置）
 */
enum class RunnerPose {
    kUnknown,
    kRun,
    kJumpUp,
    kJumpTop,
    kJumpDown,
    kSlide,
    kFlight,
};

/**
 * 游戏物体类型枚举：标识视觉层检测到的游戏内对象。
 *
 * 分为三类：
 * 1. 危险物（IsHazard 返回 true）：蛋糕断层、毒瓶、悬垂裱花袋
 * 2. 收藏品（IsCollectible 返回 true）：条纹糖果、双倍糖果、护盾令牌等
 * 3. 未知类型：视觉层无法分类的对象
 */
enum class GameObjectType {
    kUnknown,

    // 危险物
    kCakeGap,           // 蛋糕断层——需要跳跃越过
    kPoisonBottle,      // 毒瓶——当前仅记录，不提示动作
    kHangingPipingBag,  // 悬垂裱花袋——需要滑铲躲避

    // 收藏品
    kStripedCandy,      // 条纹糖果
    kDoubleCandy,       // 双倍糖果
    kShieldToken,       // 护盾令牌
    kFlightTrigger,     // 飞行触发器
    kUnknownBoost,      // 未知加速道具
};

/**
 * 提示动作枚举：HUD 向玩家推荐的操控动作。
 *
 * 由 ActionPromptEngine 综合危险 ETA、玩家姿态和跳跃阶段后输出：
 * - kNone：无推荐动作（安全或数据不足）
 * - kJump：单次跳跃（应对窄断层）
 * - kJumpAgain：二连跳（应对宽断层，宽度 >= 0.18 归一化坐标）
 * - kSlide：滑铲（应对悬垂裱花袋）
 *
 * 约束：提示必须连续两帧稳定才输出，避免单帧误检造成 HUD 闪烁。
 * 下滑提示仅在玩家着地时输出（空中无法滑铲）。
 */
enum class PromptAction {
    kNone,
    kJump,
    kJumpAgain,
    kSlide,
};

/**
 * 场景观察数据结构：视觉层对当前场景的初步判断。
 *
 * 由未来视觉模块填充，当前仅作为接口预留。
 * - hint：视觉层建议的场景模式
 * - hint_confidence：建议的可信度（0.0 ~ 1.0），低于 0.68 视为不可信
 * - occlusion_confidence：遮挡检测的可信度，高于 0.72 视为已遮挡
 */
struct SceneObservation {
    SceneMode hint{SceneMode::kUnknown};
    float hint_confidence{0.0F};
    float occlusion_confidence{0.0F};
};

/**
 * 检测到的游戏对象数据结构。
 *
 * 由视觉追踪层在每帧中输出，包含对象的类型、位置、速度和置信度。
 * - bounds：归一化坐标（0.0 ~ 1.0）
 * - velocity_x_per_second：X 方向速度，归一化坐标/秒，向左滚动时为负数
 * - danger_bounds：可选的危险区域矩形，用于精确碰撞检测
 * - track_id：对象追踪 ID，用于跨帧关联同一对象
 * - confidence：视觉层检测可信度（0.0 ~ 1.0）
 */
struct DetectedObject {
    GameObjectType type{GameObjectType::kUnknown};
    RectF bounds{};
    std::optional<RectF> danger_bounds{};
    float confidence{0.0F};
    std::int64_t track_id{-1};
    float velocity_x_per_second{0.0F};
};

/**
 * 单帧检测结果数据结构：视觉层每帧输出的完整快照。
 *
 * 这是分析引擎的唯一输入源。所有分析逻辑均基于此结构体。
 * 包含场景信息、玩家边界、对象列表、游戏状态（分数/生命/护盾）等。
 * 此结构体应为只读——分析引擎不应修改它。
 */
struct FrameDetections {
    std::int64_t timestamp_ms{0};
    SceneObservation scene{};

    std::optional<RectF> player_bounds{};
    float player_confidence{0.0F};

    /** 游戏世界整体向左滚动时为负数。0 表示当前无法可靠估计。 */
    float world_scroll_speed_x_per_second{0.0F};

    std::vector<DetectedObject> objects{};

    std::optional<int> score{};
    float score_confidence{0.0F};

    std::optional<int> heart_count{};
    float heart_confidence{0.0F};

    bool shield_active{false};
    float shield_confidence{0.0F};
};

/**
 * 角色运动状态数据结构。
 *
 * 由 RunnerStateMachine 根据玩家矩形边界和垂直速度推断得出。
 * 是下游模块（跳跃阶段估计、危险检测、提示引擎）的输入。
 * - pose：当前角色姿态
 * - grounded：是否接触地面（影响下滑提示的可用性）
 * - vertical_velocity_per_second：垂直速度，负数=向上，正数=向下
 */
struct RunnerMotion {
    RunnerPose pose{RunnerPose::kUnknown};
    std::optional<RectF> bounds{};
    float confidence{0.0F};
    float vertical_velocity_per_second{0.0F};
    bool grounded{false};
};

/**
 * 危险物预测数据结构。
 *
 * 由 HazardEtaEstimator 计算得出，描述每个危险物的预计到达时间和推荐动作。
 * vector 按 eta_ms 升序排列，最近的危险物排在前面。
 */
struct HazardForecast {
    GameObjectType type{GameObjectType::kUnknown};
    RectF danger_bounds{};
    float eta_ms{kNoEtaMs};
    float confidence{0.0F};
    PromptAction preferred_action{PromptAction::kNone};
    std::uint8_t required_jump_stage{0};
};

/**
 * 动作提示数据结构。
 *
 * 由 ActionPromptEngine 综合所有信息后输出的最终 HUD 提示。
 * 需经过连续两帧稳定验证后才返回给上层显示。
 * confidence 取 hazard.confidence 和 runner.confidence 的较小值。
 */
struct ActionPrompt {
    PromptAction action{PromptAction::kNone};
    GameObjectType target{GameObjectType::kUnknown};
    float eta_ms{kNoEtaMs};
    float confidence{0.0F};
    std::uint8_t required_jump_stage{0};
};

/**
 * 完整的分析结果数据结构。
 *
 * 由 NativeAnalysisEngine::Analyze() 输出，包含当前帧所有分析信息。
 * 将传递给上层 UI 用于渲染 HUD。
 * 包含：场景信息、角色姿态、跳跃阶段、危险列表、收藏物列表、
 * 游戏状态（分数/生命/护盾）、最终提示。
 */
struct AnalysisResult {
    SceneMode scene_mode{SceneMode::kUnknown};
    float scene_confidence{0.0F};

    RunnerMotion runner{};
    std::uint8_t jump_stage{0};

    std::vector<HazardForecast> hazards{};
    std::vector<DetectedObject> collectibles{};

    std::optional<int> score{};
    float score_confidence{0.0F};
    std::optional<int> heart_count{};
    float heart_confidence{0.0F};
    bool shield_active{false};
    float shield_confidence{0.0F};

    ActionPrompt prompt{};

    /**
     * 将分析结果序列化为 HUD 绘制数据 JSON 字符串。
     *
     * 输出格式（手动拼接，不依赖 JSON 库）：
     * {
     *   "scene": <int>,
     *   "scene_conf": <float>,
     *   "pose": <int>,
     *   "grounded": <bool>,
     *   "jump_stage": <int>,
     *   "prompt_action": <int>,
     *   "prompt_target": <int>,
     *   "prompt_eta_ms": <float>,
     *   "prompt_conf": <float>,
     *   "player": { "l": <float>, "t": <float>, "r": <float>, "b": <float> },
     *   "hazards": [
     *     { "type": <int>, "eta_ms": <float>, "conf": <float>, "action": <int>,
     *       "bounds": { "l": <float>, "t": <float>, "r": <float>, "b": <float> } }
     *   ],
     *   "collectibles_count": <int>
     * }
     *
     * @return JSON 字符串，包含所有 HUD 绘制所需数据
     */
    std::string serializeDrawingData() const;
};

/** 判断对象类型是否为可收集物品（条纹糖果、双倍糖果、护盾令牌等）。 */
inline bool IsCollectible(GameObjectType type) {
    return type == GameObjectType::kStripedCandy ||
        type == GameObjectType::kDoubleCandy ||
        type == GameObjectType::kShieldToken ||
        type == GameObjectType::kFlightTrigger ||
        type == GameObjectType::kUnknownBoost;
}

/** 判断对象类型是否为危险物（蛋糕断层、毒瓶、悬垂裱花袋）。 */
inline bool IsHazard(GameObjectType type) {
    return type == GameObjectType::kCakeGap ||
        type == GameObjectType::kPoisonBottle ||
        type == GameObjectType::kHangingPipingBag;
}

/** 返回场景是否处于飞行模式。飞行模式下应抑制所有跳跃/滑铲提示。 */
inline bool IsFlightMode(SceneMode mode) {
    return mode == SceneMode::kFlightRun;
}

/** 返回场景是否处于遮挡状态。遮挡下应降低置信度或抑制低置信度提示。 */
inline bool IsOccluded(SceneMode mode) {
    return mode == SceneMode::kOccluded;
}

/** 返回场景是否为有效的跑酷分析目标。仅在 GroundRun 或 FlightRun 下输出完整分析。 */
inline bool IsAnalyzableScene(SceneMode mode) {
    return mode == SceneMode::kGroundRun || mode == SceneMode::kFlightRun;
}

}  // namespace hzzs::analysis
