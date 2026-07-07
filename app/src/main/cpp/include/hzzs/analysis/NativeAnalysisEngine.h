#pragma once

#include "hzzs/analysis/ActionPromptEngine.h"
#include "hzzs/analysis/RunnerStateMachine.h"

namespace hzzs::analysis {

/**
 * C++ 算法总入口。
 * 后续 Kotlin / JNI / 视觉层把 FrameDetections 交给这里，
 * 再把 AnalysisResult 回传给 HUD、记录与战报模块。
 */
class NativeAnalysisEngine {
public:
    AnalysisResult Analyze(const FrameDetections& frame);
    void Reset();

private:
    RunnerStateMachine runner_state_machine_{};
    ActionPromptEngine action_prompt_engine_{};
};

}  // namespace hzzs::analysis
