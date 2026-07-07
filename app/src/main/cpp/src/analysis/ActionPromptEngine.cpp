#include "hzzs/analysis/ActionPromptEngine.h"

namespace hzzs::analysis {
namespace {

constexpr float kMinPromptEtaMs = 130.0F;
constexpr float kMaxPromptEtaMs = 900.0F;
constexpr float kMinPromptConfidence = 0.72F;
constexpr int kRequiredStableFrames = 2;

bool IsEligible(float eta_ms, float confidence) {
    return (
        eta_ms >= kMinPromptEtaMs &&
        eta_ms <= kMaxPromptEtaMs &&
        confidence >= kMinPromptConfidence
    );
}

PromptAction SelectCandidate(const FrameDetections& frame) {
    const bool has_ground_hazard = IsEligible(
        frame.ground_hazard_eta_ms,
        frame.ground_hazard_confidence
    );

    const bool has_overhead_hazard = IsEligible(
        frame.overhead_hazard_eta_ms,
        frame.overhead_hazard_confidence
    );

    if (!has_ground_hazard && !has_overhead_hazard) {
        return PromptAction::kNone;
    }

    if (has_ground_hazard && !has_overhead_hazard) {
        return PromptAction::kJump;
    }

    if (!has_ground_hazard && has_overhead_hazard) {
        return PromptAction::kSlide;
    }

    return frame.ground_hazard_eta_ms <= frame.overhead_hazard_eta_ms
        ? PromptAction::kJump
        : PromptAction::kSlide;
}

}  // namespace

PromptAction ActionPromptEngine::Update(const FrameDetections& frame) {
    const PromptAction candidate = SelectCandidate(frame);

    if (candidate == PromptAction::kNone) {
        Reset();
        return PromptAction::kNone;
    }

    if (candidate != pending_action_) {
        pending_action_ = candidate;
        stable_frame_count_ = 1;
        return PromptAction::kNone;
    }

    stable_frame_count_++;

    if (
        stable_frame_count_ < kRequiredStableFrames ||
        candidate == emitted_action_
    ) {
        return PromptAction::kNone;
    }

    emitted_action_ = candidate;
    return candidate;
}

void ActionPromptEngine::Reset() {
    pending_action_ = PromptAction::kNone;
    emitted_action_ = PromptAction::kNone;
    stable_frame_count_ = 0;
}

}  // namespace hzzs::analysis
