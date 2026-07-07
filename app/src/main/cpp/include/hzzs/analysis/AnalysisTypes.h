#pragma once

#include <cstdint>
#include <optional>

#include "hzzs/analysis/Geometry.h"

namespace hzzs::analysis {

constexpr float kNoEtaMs = -1.0F;

enum class RunnerPose {
    kUnknown,
    kRun,
    kJumpUp,
    kJumpTop,
    kJumpDown,
    kSlide,
};

enum class PromptAction {
    kNone,
    kJump,
    kSlide,
};

/**
 * 后续视觉识别层应输出这一结构。
 * 这里仅定义算法输入，不包含屏幕采集、OpenCV 或 UI 逻辑。
 */
struct FrameDetections {
    std::int64_t timestamp_ms{0};

    std::optional<RectF> player_bounds{};
    float player_confidence{0.0F};

    float ground_hazard_eta_ms{kNoEtaMs};
    float ground_hazard_confidence{0.0F};

    float overhead_hazard_eta_ms{kNoEtaMs};
    float overhead_hazard_confidence{0.0F};

    std::optional<RectF> nearest_candy_bounds{};
    float candy_confidence{0.0F};
};

struct AnalysisResult {
    RunnerPose pose{RunnerPose::kUnknown};
    float pose_confidence{0.0F};

    PromptAction suggested_action{PromptAction::kNone};
    float action_confidence{0.0F};

    float ground_hazard_eta_ms{kNoEtaMs};
    float overhead_hazard_eta_ms{kNoEtaMs};
};

}  // namespace hzzs::analysis
