#include "hzzs/analysis/ActionPromptEngine.h"

#include <algorithm>
#include <cmath>

namespace hzzs::analysis {
namespace {

constexpr float kMinPromptEtaMs = 170.0F;
constexpr float kMaxPromptEtaMs = 900.0F;
constexpr float kMinPromptConfidence = 0.72F;
constexpr int kRequiredStableFrames = 2;

bool IsSamePrompt(
    const ActionPrompt& left,
    const ActionPrompt& right
) {
    return left.action == right.action &&
        left.target == right.target &&
        left.required_jump_stage == right.required_jump_stage;
}

ActionPrompt FindCandidate(
    const RunnerMotion& runner,
    std::uint8_t jump_stage,
    const std::vector<HazardForecast>& hazards
) {
    for (const HazardForecast& hazard : hazards) {
        if (
            hazard.preferred_action == PromptAction::kNone ||
            hazard.eta_ms < kMinPromptEtaMs ||
            hazard.eta_ms > kMaxPromptEtaMs ||
            hazard.confidence < kMinPromptConfidence
        ) {
            continue;
        }

        ActionPrompt candidate{};
        candidate.target = hazard.type;
        candidate.eta_ms = hazard.eta_ms;
        candidate.confidence = std::min(
            hazard.confidence,
            runner.confidence
        );
        candidate.required_jump_stage = hazard.required_jump_stage;

        if (hazard.preferred_action == PromptAction::kSlide) {
            if (!runner.grounded) {
                continue;
            }

            candidate.action = PromptAction::kSlide;
            return candidate;
        }

        if (hazard.preferred_action == PromptAction::kJump) {
            if (jump_stage == 0) {
                candidate.action = PromptAction::kJump;
                return candidate;
            }

            if (
                jump_stage == 1 &&
                hazard.required_jump_stage >= kMaxJumpStage
            ) {
                candidate.action = PromptAction::kJumpAgain;
                return candidate;
            }
        }
    }

    return {};
}

}  // namespace

ActionPrompt ActionPromptEngine::Update(
    SceneMode scene_mode,
    const RunnerMotion& runner,
    std::uint8_t jump_stage,
    const std::vector<HazardForecast>& hazards
) {
    if (
        scene_mode != SceneMode::kGroundRun ||
        runner.pose == RunnerPose::kUnknown ||
        !runner.bounds.has_value()
    ) {
        Reset();
        return {};
    }

    const ActionPrompt candidate = FindCandidate(
        runner,
        jump_stage,
        hazards
    );

    if (candidate.action == PromptAction::kNone) {
        Reset();
        return {};
    }

    if (!IsSamePrompt(candidate, pending_prompt_)) {
        pending_prompt_ = candidate;
        stable_frame_count_ = 1;
        active_prompt_ = {};
        return {};
    }

    stable_frame_count_++;

    if (stable_frame_count_ >= kRequiredStableFrames) {
        active_prompt_ = candidate;
    }

    return active_prompt_;
}

void ActionPromptEngine::Reset() {
    pending_prompt_ = {};
    active_prompt_ = {};
    stable_frame_count_ = 0;
}

}  // namespace hzzs::analysis
