#include "hzzs/analysis/RunnerStateMachine.h"

#include <algorithm>

namespace hzzs::analysis {
namespace {

constexpr float kMinPlayerConfidence = 0.35F;
constexpr float kAirborneThreshold = 0.018F;
constexpr float kSlideHeightRatio = 0.76F;
constexpr float kUpwardVelocityThreshold = -0.070F;
constexpr float kDownwardVelocityThreshold = 0.070F;
constexpr float kBaselineSmoothing = 0.08F;

float Lerp(float from, float to, float amount) {
    return from + (to - from) * amount;
}

}  // namespace

RunnerMotion RunnerStateMachine::Update(
    const FrameDetections& frame,
    SceneMode scene_mode
) {
    RunnerMotion result{};

    if (
        !frame.player_bounds.has_value() ||
        !frame.player_bounds->IsValid() ||
        frame.player_confidence < kMinPlayerConfidence
    ) {
        return result;
    }

    result.bounds = frame.player_bounds;
    result.confidence = frame.player_confidence;

    if (scene_mode == SceneMode::kFlightRun) {
        result.pose = RunnerPose::kFlight;
        result.grounded = false;
        return result;
    }

    if (scene_mode != SceneMode::kGroundRun) {
        return result;
    }

    const RectF& player = *frame.player_bounds;
    const float player_bottom = player.bottom;
    const float player_height = player.Height();

    if (!has_baseline_) {
        has_baseline_ = true;
        baseline_bottom_ = player_bottom;
        baseline_height_ = player_height;
        last_player_bottom_ = player_bottom;
        last_timestamp_ms_ = frame.timestamp_ms;

        result.pose = RunnerPose::kRun;
        result.grounded = true;
        return result;
    }

    float delta_seconds = 1.0F / 60.0F;

    if (frame.timestamp_ms > last_timestamp_ms_ && last_timestamp_ms_ > 0) {
        delta_seconds = std::max(
            0.001F,
            static_cast<float>(frame.timestamp_ms - last_timestamp_ms_) / 1000.0F
        );
    }

    const float vertical_velocity = (
        player_bottom - last_player_bottom_
    ) / delta_seconds;

    const float airborne_gap = baseline_bottom_ - player_bottom;
    const bool near_ground = airborne_gap <= kAirborneThreshold;

    result.vertical_velocity_per_second = vertical_velocity;

    if (near_ground) {
        const bool looks_like_slide = (
            baseline_height_ > 0.0F &&
            player_height < baseline_height_ * kSlideHeightRatio
        );

        if (looks_like_slide) {
            result.pose = RunnerPose::kSlide;
            result.grounded = true;
        } else {
            baseline_bottom_ = Lerp(
                baseline_bottom_,
                player_bottom,
                kBaselineSmoothing
            );

            baseline_height_ = Lerp(
                baseline_height_,
                player_height,
                kBaselineSmoothing
            );

            result.pose = RunnerPose::kRun;
            result.grounded = true;
        }
    } else if (vertical_velocity < kUpwardVelocityThreshold) {
        result.pose = RunnerPose::kJumpUp;
    } else if (vertical_velocity > kDownwardVelocityThreshold) {
        result.pose = RunnerPose::kJumpDown;
    } else {
        result.pose = RunnerPose::kJumpTop;
    }

    last_player_bottom_ = player_bottom;
    last_timestamp_ms_ = frame.timestamp_ms;

    return result;
}

void RunnerStateMachine::Reset() {
    has_baseline_ = false;
    baseline_bottom_ = 0.0F;
    baseline_height_ = 0.0F;
    last_player_bottom_ = 0.0F;
    last_timestamp_ms_ = 0;
}

}  // namespace hzzs::analysis
