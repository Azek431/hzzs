#include "hzzs/analysis/HazardEtaEstimator.h"

#include <algorithm>
#include <cmath>

namespace hzzs::analysis {
namespace {

constexpr float kMinHazardConfidence = 0.62F;
constexpr float kMinLeftwardSpeed = 0.04F;
constexpr float kPlayerSafetyMargin = 0.014F;
constexpr float kMaxUsefulEtaMs = 2200.0F;
constexpr float kWideGapThreshold = 0.18F;

}  // namespace

std::vector<HazardForecast> HazardEtaEstimator::Estimate(
    const FrameDetections& frame,
    const RunnerMotion& runner
) const {
    std::vector<HazardForecast> forecasts{};

    if (!runner.bounds.has_value() || !runner.bounds->IsValid()) {
        return forecasts;
    }

    const float player_collision_x = runner.bounds->right;

    for (const DetectedObject& object : frame.objects) {
        if (!IsHazard(object.type) || !object.bounds.IsValid()) {
            continue;
        }

        if (object.confidence < kMinHazardConfidence) {
            continue;
        }

        const RectF danger_bounds = ResolveDangerBounds(object);
        const float leftward_speed = ResolveLeftwardSpeed(frame, object);

        if (leftward_speed < kMinLeftwardSpeed) {
            continue;
        }

        const float distance = danger_bounds.left - player_collision_x - kPlayerSafetyMargin;

        if (distance <= 0.0F) {
            continue;
        }

        const float eta_ms = distance / leftward_speed * 1000.0F;

        if (eta_ms > kMaxUsefulEtaMs) {
            continue;
        }

        HazardForecast forecast{};
        forecast.type = object.type;
        forecast.danger_bounds = danger_bounds;
        forecast.eta_ms = eta_ms;
        forecast.confidence = object.confidence;
        forecast.preferred_action = DefaultActionFor(object.type);
        forecast.required_jump_stage = RequiredJumpStageFor(
            object.type,
            danger_bounds
        );

        forecasts.push_back(forecast);
    }

    std::sort(
        forecasts.begin(),
        forecasts.end(),
        [](const HazardForecast& left, const HazardForecast& right) {
            return left.eta_ms < right.eta_ms;
        }
    );

    return forecasts;
}

float HazardEtaEstimator::ResolveLeftwardSpeed(
    const FrameDetections& frame,
    const DetectedObject& object
) {
    if (object.velocity_x_per_second < -kMinLeftwardSpeed) {
        return std::abs(object.velocity_x_per_second);
    }

    if (frame.world_scroll_speed_x_per_second < -kMinLeftwardSpeed) {
        return std::abs(frame.world_scroll_speed_x_per_second);
    }

    return 0.0F;
}

RectF HazardEtaEstimator::ResolveDangerBounds(const DetectedObject& object) {
    return object.danger_bounds.value_or(object.bounds);
}

PromptAction HazardEtaEstimator::DefaultActionFor(GameObjectType type) {
    switch (type) {
        case GameObjectType::kCakeGap:
            return PromptAction::kJump;

        case GameObjectType::kHangingPipingBag:
            return PromptAction::kSlide;

        case GameObjectType::kPoisonBottle:
            // 先只绘制和记录。等使用真实回放完成碰撞规则校准后，
            // 再决定是否提示跳跃或其他动作，避免不可靠建议。
            return PromptAction::kNone;

        default:
            return PromptAction::kNone;
    }
}

std::uint8_t HazardEtaEstimator::RequiredJumpStageFor(
    GameObjectType type,
    const RectF& danger_bounds
) {
    if (type != GameObjectType::kCakeGap) {
        return 0;
    }

    return danger_bounds.Width() >= kWideGapThreshold
        ? kMaxJumpStage
        : 1;
}

}  // namespace hzzs::analysis
