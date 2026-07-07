#pragma once

#include <vector>

#include "hzzs/analysis/AnalysisTypes.h"

namespace hzzs::analysis {

/**
 * 将已识别和追踪的危险物转换为 ETA。
 *
 * ETA 只在对象稳定、速度可信并且处于玩家前方时输出。
 */
class HazardEtaEstimator {
public:
    std::vector<HazardForecast> Estimate(
        const FrameDetections& frame,
        const RunnerMotion& runner
    ) const;

private:
    static float ResolveLeftwardSpeed(
        const FrameDetections& frame,
        const DetectedObject& object
    );

    static RectF ResolveDangerBounds(const DetectedObject& object);

    static PromptAction DefaultActionFor(GameObjectType type);

    static std::uint8_t RequiredJumpStageFor(
        GameObjectType type,
        const RectF& danger_bounds
    );
};

}  // namespace hzzs::analysis
