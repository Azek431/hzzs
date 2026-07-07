#pragma once

#include "hzzs/analysis/AnalysisTypes.h"

namespace hzzs::analysis {

/**
 * 根据连续帧中的玩家逻辑坐标推断基础跑酷姿态。
 */
class RunnerStateMachine {
public:
    RunnerPose Update(const FrameDetections& frame);
    void Reset();

private:
    bool has_baseline_{false};
    float baseline_bottom_{0.0F};
    float baseline_height_{0.0F};
    float last_player_bottom_{0.0F};
    std::int64_t last_timestamp_ms_{0};
};

}  // namespace hzzs::analysis
