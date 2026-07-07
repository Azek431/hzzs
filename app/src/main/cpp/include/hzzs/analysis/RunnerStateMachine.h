#pragma once

#include "hzzs/analysis/AnalysisTypes.h"

namespace hzzs::analysis {

/**
 * 根据连续帧的玩家边界推断跑步、起跳、滞空、下落和下滑。
 *
 * 该类不关心屏幕采集、模板匹配或 UI；它只消费稳定后的玩家矩形。
 */
class RunnerStateMachine {
public:
    RunnerMotion Update(const FrameDetections& frame, SceneMode scene_mode);

    void Reset();

private:
    bool has_baseline_{false};
    float baseline_bottom_{0.0F};
    float baseline_height_{0.0F};
    float last_player_bottom_{0.0F};
    std::int64_t last_timestamp_ms_{0};
};

}  // namespace hzzs::analysis
