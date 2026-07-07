#include "hzzs/analysis/SceneStateMachine.h"

namespace hzzs::analysis {
namespace {

constexpr float kMinHintConfidence = 0.68F;
constexpr float kOcclusionThreshold = 0.72F;
constexpr int kTransitionConfirmFrames = 2;

}  // namespace

SceneMode SceneStateMachine::Update(const SceneObservation& observation) {
    if (observation.occlusion_confidence >= kOcclusionThreshold) {
        pending_scene_ = SceneMode::kUnknown;
        pending_frame_count_ = 0;
        return SceneMode::kOccluded;
    }

    const SceneMode candidate = observation.hint_confidence >= kMinHintConfidence
        ? observation.hint
        : SceneMode::kUnknown;

    if (candidate == SceneMode::kUnknown) {
        pending_scene_ = SceneMode::kUnknown;
        pending_frame_count_ = 0;
        return current_scene_;
    }

    if (current_scene_ == SceneMode::kUnknown) {
        current_scene_ = candidate;
        pending_scene_ = candidate;
        pending_frame_count_ = 0;
        return current_scene_;
    }

    if (candidate == current_scene_) {
        pending_scene_ = candidate;
        pending_frame_count_ = 0;
        return current_scene_;
    }

    if (candidate != pending_scene_) {
        pending_scene_ = candidate;
        pending_frame_count_ = 1;
        return current_scene_;
    }

    pending_frame_count_++;

    if (pending_frame_count_ >= kTransitionConfirmFrames) {
        current_scene_ = candidate;
        pending_frame_count_ = 0;
    }

    return current_scene_;
}

void SceneStateMachine::Reset() {
    current_scene_ = SceneMode::kUnknown;
    pending_scene_ = SceneMode::kUnknown;
    pending_frame_count_ = 0;
}

}  // namespace hzzs::analysis
