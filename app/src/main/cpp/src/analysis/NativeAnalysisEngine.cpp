#include "hzzs/analysis/NativeAnalysisEngine.h"

namespace hzzs::analysis {
namespace {

float GetActionConfidence(
    PromptAction action,
    const FrameDetections& frame
) {
    switch (action) {
        case PromptAction::kJump:
            return frame.ground_hazard_confidence;
        case PromptAction::kSlide:
            return frame.overhead_hazard_confidence;
        case PromptAction::kNone:
            return 0.0F;
    }

    return 0.0F;
}

float GetPoseConfidence(
    RunnerPose pose,
    const FrameDetections& frame
) {
    return pose == RunnerPose::kUnknown
        ? 0.0F
        : frame.player_confidence;
}

}  // namespace

AnalysisResult NativeAnalysisEngine::Analyze(
    const FrameDetections& frame
) {
    AnalysisResult result{};

    result.pose = runner_state_machine_.Update(frame);
    result.pose_confidence = GetPoseConfidence(result.pose, frame);

    result.suggested_action = action_prompt_engine_.Update(frame);
    result.action_confidence = GetActionConfidence(
        result.suggested_action,
        frame
    );

    result.ground_hazard_eta_ms = frame.ground_hazard_eta_ms;
    result.overhead_hazard_eta_ms = frame.overhead_hazard_eta_ms;

    return result;
}

void NativeAnalysisEngine::Reset() {
    runner_state_machine_.Reset();
    action_prompt_engine_.Reset();
}

}  // namespace hzzs::analysis
