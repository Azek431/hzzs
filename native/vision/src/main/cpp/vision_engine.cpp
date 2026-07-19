#include "vision_engine.h"

#include <algorithm>
#include <cmath>

namespace hzzs {
namespace {
bool valid_frame(const FrameView& frame) {
    constexpr int kMaxDimension = 8192;
    return frame.pixels != nullptr && frame.width > 0 && frame.height > 0 &&
           frame.width <= kMaxDimension && frame.height <= kMaxDimension;
}

float finite_confidence(float value) {
    return std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 0.0f;
}
}  // namespace

Result analyze(int scene, const FrameView& frame, int work_width) {
    if (!valid_frame(frame)) {
        Result invalid;
        invalid.error = "invalid frame";
        return invalid;
    }
    if (scene < 0 || scene > 1) {
        Result invalid;
        invalid.error = "invalid scene";
        return invalid;
    }
    if (work_width < 160 || work_width > 720) {
        Result invalid;
        invalid.error = "invalid work width";
        return invalid;
    }

    Result result = scene == 1 ? analyze_bamboo(frame, work_width) : analyze_sweet(frame, work_width);
    result.scene_confidence = finite_confidence(result.scene_confidence);
    for (auto& detection : result.detections) {
        detection.bounds.left = std::isfinite(detection.bounds.left) ? std::clamp(detection.bounds.left, 0.0f, 1.0f) : 0.0f;
        detection.bounds.top = std::isfinite(detection.bounds.top) ? std::clamp(detection.bounds.top, 0.0f, 1.0f) : 0.0f;
        detection.bounds.right = std::isfinite(detection.bounds.right) ? std::clamp(detection.bounds.right, detection.bounds.left, 1.0f) : detection.bounds.left;
        detection.bounds.bottom = std::isfinite(detection.bounds.bottom) ? std::clamp(detection.bounds.bottom, detection.bounds.top, 1.0f) : detection.bounds.top;
        detection.confidence = finite_confidence(detection.confidence);
        if (detection.bounds.right <= detection.bounds.left || detection.bounds.bottom <= detection.bounds.top) {
            detection.actionable = false;
            detection.diagnostic_only = true;
        }
        if (detection.avoidance == Avoidance::NONE) detection.actionable = false;
    }

    const auto player = std::find_if(
        result.detections.begin(),
        result.detections.end(),
        [](const Detection& detection) { return detection.kind == Kind::PLAYER; });
    if (player == result.detections.end()) {
        for (auto& detection : result.detections) detection.actionable = false;
        return result;
    }
    for (auto& detection : result.detections) {
        if (detection.kind == Kind::PLAYER) continue;
        const bool behind = detection.bounds.right <= player->bounds.left;
        if (behind || detection.diagnostic_only) detection.actionable = false;
    }
    return result;
}

void reset() {}
}  // namespace hzzs
