#pragma once

#include <cstdint>
#include <optional>
#include <vector>

#include "hzzs/analysis/Geometry.h"

namespace hzzs::analysis {

constexpr float kNoEtaMs = -1.0F;
constexpr std::uint8_t kMaxJumpStage = 2;

enum class SceneMode {
    kUnknown,
    kMenu,
    kCountdown,
    kGroundRun,
    kFlightRun,
    kResult,
    kOccluded,
};

enum class RunnerPose {
    kUnknown,
    kRun,
    kJumpUp,
    kJumpTop,
    kJumpDown,
    kSlide,
    kFlight,
};

enum class GameObjectType {
    kUnknown,
    kCakeGap,
    kPoisonBottle,
    kHangingPipingBag,
    kStripedCandy,
    kDoubleCandy,
    kShieldToken,
    kFlightTrigger,
    kUnknownBoost,
};

enum class PromptAction {
    kNone,
    kJump,
    kJumpAgain,
    kSlide,
};

struct SceneObservation {
    SceneMode hint{SceneMode::kUnknown};
    float hint_confidence{0.0F};
    float occlusion_confidence{0.0F};
};

/**
 * 由未来视觉与追踪层提供的稳定对象。
 *
 * velocity_x_per_second 使用归一化坐标 / 秒；世界向左滚动时通常为负数。
 * danger_bounds 可用于悬垂裱花袋的尖嘴、断层的真实起点等碰撞关键区域。
 */
struct DetectedObject {
    GameObjectType type{GameObjectType::kUnknown};
    RectF bounds{};
    std::optional<RectF> danger_bounds{};
    float confidence{0.0F};
    std::int64_t track_id{-1};
    float velocity_x_per_second{0.0F};
};

struct FrameDetections {
    std::int64_t timestamp_ms{0};
    SceneObservation scene{};

    std::optional<RectF> player_bounds{};
    float player_confidence{0.0F};

    /**
     * 游戏世界整体向左滚动时为负数。
     * 未来由背景 / 多目标跟踪模块估计；0 表示当前无法可靠估计。
     */
    float world_scroll_speed_x_per_second{0.0F};

    std::vector<DetectedObject> objects{};

    std::optional<int> score{};
    float score_confidence{0.0F};

    std::optional<int> heart_count{};
    float heart_confidence{0.0F};

    bool shield_active{false};
    float shield_confidence{0.0F};
};

struct RunnerMotion {
    RunnerPose pose{RunnerPose::kUnknown};
    std::optional<RectF> bounds{};
    float confidence{0.0F};
    float vertical_velocity_per_second{0.0F};
    bool grounded{false};
};

struct HazardForecast {
    GameObjectType type{GameObjectType::kUnknown};
    RectF danger_bounds{};
    float eta_ms{kNoEtaMs};
    float confidence{0.0F};
    PromptAction preferred_action{PromptAction::kNone};
    std::uint8_t required_jump_stage{0};
};

struct ActionPrompt {
    PromptAction action{PromptAction::kNone};
    GameObjectType target{GameObjectType::kUnknown};
    float eta_ms{kNoEtaMs};
    float confidence{0.0F};
    std::uint8_t required_jump_stage{0};
};

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
};

inline bool IsCollectible(GameObjectType type) {
    return type == GameObjectType::kStripedCandy ||
        type == GameObjectType::kDoubleCandy ||
        type == GameObjectType::kShieldToken ||
        type == GameObjectType::kFlightTrigger ||
        type == GameObjectType::kUnknownBoost;
}

inline bool IsHazard(GameObjectType type) {
    return type == GameObjectType::kCakeGap ||
        type == GameObjectType::kPoisonBottle ||
        type == GameObjectType::kHangingPipingBag;
}

}  // namespace hzzs::analysis
