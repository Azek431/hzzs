#include "hzzs/analysis/NativeAnalysisEngine.h"

namespace hzzs::analysis {

AnalysisResult NativeAnalysisEngine::Analyze(const FrameDetections& frame) {
    AnalysisResult result{};

    result.scene_mode = scene_state_machine_.Update(frame.scene);
    result.scene_confidence = result.scene_mode == SceneMode::kUnknown
        ? 0.0F
        : frame.scene.hint_confidence;

    result.runner = runner_state_machine_.Update(
        frame,
        result.scene_mode
    );

    result.jump_stage = jump_stage_estimator_.Update(
        result.runner,
        result.scene_mode,
        frame.timestamp_ms
    );

    if (result.scene_mode == SceneMode::kGroundRun) {
        result.hazards = hazard_eta_estimator_.Estimate(frame, result.runner);
        result.prompt = action_prompt_engine_.Update(
            result.scene_mode,
            result.runner,
            result.jump_stage,
            result.hazards
        );
    } else {
        action_prompt_engine_.Reset();
    }

    for (const DetectedObject& object : frame.objects) {
        if (IsCollectible(object.type) && object.bounds.IsValid()) {
            result.collectibles.push_back(object);
        }
    }

    result.score = frame.score;
    result.score_confidence = frame.score_confidence;
    result.heart_count = frame.heart_count;
    result.heart_confidence = frame.heart_confidence;
    result.shield_active = frame.shield_active;
    result.shield_confidence = frame.shield_confidence;

    return result;
}

void NativeAnalysisEngine::Reset() {
    scene_state_machine_.Reset();
    runner_state_machine_.Reset();
    jump_stage_estimator_.Reset();
    action_prompt_engine_.Reset();
}

}  // namespace hzzs::analysis
