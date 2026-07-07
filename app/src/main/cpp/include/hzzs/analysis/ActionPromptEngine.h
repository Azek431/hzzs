#pragma once

#include <vector>

#include "hzzs/analysis/AnalysisTypes.h"

namespace hzzs::analysis {

/**
 * 根据危险 ETA、玩家状态和二连跳余量输出只读提示。
 *
 * 提示需连续两帧稳定才显示，避免单帧误检造成 HUD 闪烁。
 */
class ActionPromptEngine {
public:
    ActionPrompt Update(
        SceneMode scene_mode,
        const RunnerMotion& runner,
        std::uint8_t jump_stage,
        const std::vector<HazardForecast>& hazards
    );

    void Reset();

private:
    ActionPrompt pending_prompt_{};
    ActionPrompt active_prompt_{};
    int stable_frame_count_{0};
};

}  // namespace hzzs::analysis
