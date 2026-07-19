#include <algorithm>
#include <cstdint>
#include "../../native/vision/src/main/cpp/vision_engine.h"

extern "C" int hzzs_analyze_host(
    int scene,
    const uint32_t* pixels,
    int width,
    int height,
    int work_width,
    float* output,
    int max_detections) {
    if (!output || max_detections <= 0) return -1;
    const auto result = hzzs::analyze(scene, {pixels, width, height}, work_width);
    if (!result.error.empty()) return -2;
    output[0] = result.scene_confidence;
    const int count = std::min(max_detections, static_cast<int>(result.detections.size()));
    for (int i = 0; i < count; ++i) {
        const auto& d = result.detections[static_cast<size_t>(i)];
        float* row = output + 1 + i * 10;
        row[0] = static_cast<float>(d.track_hint);
        row[1] = static_cast<float>(d.kind);
        row[2] = d.bounds.left;
        row[3] = d.bounds.top;
        row[4] = d.bounds.right;
        row[5] = d.bounds.bottom;
        row[6] = d.confidence;
        row[7] = d.actionable ? 1.0f : 0.0f;
        row[8] = d.diagnostic_only ? 1.0f : 0.0f;
        row[9] = static_cast<float>(d.avoidance);
    }
    return count;
}
