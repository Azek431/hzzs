#pragma once

#include <cstdint>

#include "hzzs/analysis/AnalysisTypes.h"

namespace hzzs::analysis {

/**
 * 对菜单、倒计时、地面跑酷、飞行、结算和外部遮挡做轻量稳定化。
 * 视觉层负责提供 scene.hint；这里负责避免单帧切场景抖动。
 */
class SceneStateMachine {
public:
    SceneMode Update(const SceneObservation& observation);

    [[nodiscard]] SceneMode Current() const {
        return current_scene_;
    }

    void Reset();

private:
    SceneMode current_scene_{SceneMode::kUnknown};
    SceneMode pending_scene_{SceneMode::kUnknown};
    int pending_frame_count_{0};
};

}  // namespace hzzs::analysis
