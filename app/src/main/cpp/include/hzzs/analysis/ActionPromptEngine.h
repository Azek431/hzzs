#pragma once

#include "hzzs/analysis/AnalysisTypes.h"

namespace hzzs::analysis {

/**
 * 根据障碍 ETA 与置信度生成跳跃或下滑提示。
 * 同一种候选动作需要连续确认两帧，降低单帧抖动误提示。
 */
class ActionPromptEngine {
public:
    PromptAction Update(const FrameDetections& frame);
    void Reset();

private:
    PromptAction pending_action_{PromptAction::kNone};
    PromptAction emitted_action_{PromptAction::kNone};
    int stable_frame_count_{0};
};

}  // namespace hzzs::analysis
