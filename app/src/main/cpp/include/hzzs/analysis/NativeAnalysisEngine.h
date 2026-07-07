#pragma once

#include "hzzs/analysis/ActionPromptEngine.h"
#include "hzzs/analysis/HazardEtaEstimator.h"
#include "hzzs/analysis/JumpStageEstimator.h"
#include "hzzs/analysis/RunnerStateMachine.h"
#include "hzzs/analysis/SceneStateMachine.h"

namespace hzzs::analysis {

/**
 * HZZS C++ 分析核心。
 *
 * 输入：视觉层提供的 FrameDetections。
 * 输出：场景、角色姿态、二连跳阶段、危险 ETA、收藏物与 HUD 提示。
 */
class NativeAnalysisEngine {
public:
    AnalysisResult Analyze(const FrameDetections& frame);

    void Reset();

private:
    SceneStateMachine scene_state_machine_{};
    RunnerStateMachine runner_state_machine_{};
    JumpStageEstimator jump_stage_estimator_{};
    HazardEtaEstimator hazard_eta_estimator_{};
    ActionPromptEngine action_prompt_engine_{};
};

}  // namespace hzzs::analysis
