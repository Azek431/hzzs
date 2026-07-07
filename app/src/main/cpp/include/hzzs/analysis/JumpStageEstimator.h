#pragma once

#include <cstdint>

#include "hzzs/analysis/AnalysisTypes.h"

namespace hzzs::analysis {

/**
 * 从玩家的垂直运动中估计跳跃段数。
 *
 * 当前游戏规则按用户确认固定为：地面 0 段、首跳 1 段、二连跳 2 段。
 * 这里只识别视觉上的再次向上加速，不执行任何触摸或自动操作。
 */
class JumpStageEstimator {
public:
    std::uint8_t Update(
        const RunnerMotion& motion,
        SceneMode scene_mode,
        std::int64_t timestamp_ms
    );

    void Reset();

private:
    std::uint8_t stage_{0};
    bool last_grounded_{true};
    float last_vertical_velocity_{0.0F};
    std::int64_t last_jump_impulse_ms_{0};
};

}  // namespace hzzs::analysis
