#include "hzzs/analysis/JumpStageEstimator.h"

namespace hzzs::analysis {
namespace {

constexpr float kNewJumpUpVelocityThreshold = -0.070F;
constexpr float kSecondJumpImpulseThreshold = -0.100F;
constexpr float kPriorVelocityNearApexThreshold = -0.015F;
constexpr std::int64_t kMinImpulseGapMs = 95;

}  // namespace

std::uint8_t JumpStageEstimator::Update(
    const RunnerMotion& motion,
    SceneMode scene_mode,
    std::int64_t timestamp_ms
) {
    if (scene_mode != SceneMode::kGroundRun || motion.pose == RunnerPose::kUnknown) {
        Reset();
        return stage_;
    }

    if (motion.grounded) {
        stage_ = 0;
        last_grounded_ = true;
        last_vertical_velocity_ = motion.vertical_velocity_per_second;
        return stage_;
    }

    const bool starts_first_jump = (
        last_grounded_ &&
        motion.vertical_velocity_per_second <= kNewJumpUpVelocityThreshold
    );

    const bool gets_second_jump_impulse = (
        !last_grounded_ &&
        stage_ == 1 &&
        motion.vertical_velocity_per_second <= kSecondJumpImpulseThreshold &&
        last_vertical_velocity_ >= kPriorVelocityNearApexThreshold &&
        timestamp_ms - last_jump_impulse_ms_ >= kMinImpulseGapMs
    );

    if (starts_first_jump || (stage_ == 0 && motion.pose == RunnerPose::kJumpUp)) {
        stage_ = 1;
        last_jump_impulse_ms_ = timestamp_ms;
    } else if (gets_second_jump_impulse) {
        stage_ = kMaxJumpStage;
        last_jump_impulse_ms_ = timestamp_ms;
    }

    last_grounded_ = false;
    last_vertical_velocity_ = motion.vertical_velocity_per_second;

    return stage_;
}

void JumpStageEstimator::Reset() {
    stage_ = 0;
    last_grounded_ = true;
    last_vertical_velocity_ = 0.0F;
    last_jump_impulse_ms_ = 0;
}

}  // namespace hzzs::analysis
