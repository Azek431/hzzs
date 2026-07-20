#include <algorithm>
#include <cstdint>
#include "../../app/src/main/cpp/vision_engine.h"

namespace {
int write_result(const hzzs::Result& result, float* output, int max_detections) {
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
}  // namespace

extern "C" int hzzs_analyze_host_config(
    int scene,
    const uint32_t* pixels,
    int width,
    int height,
    int work_width,
    int enabled_kind_mask,
    bool detect_player,
    float fixed_player_x_ratio,
    float* output,
    int max_detections) {
    if (!output || max_detections <= 0) return -1;
    return write_result(
        hzzs::analyze(
            scene,
            {pixels, width, height},
            work_width,
            enabled_kind_mask,
            detect_player,
            fixed_player_x_ratio),
        output,
        max_detections);
}

extern "C" int hzzs_analyze_host(
    int scene,
    const uint32_t* pixels,
    int width,
    int height,
    int work_width,
    float* output,
    int max_detections) {
    return hzzs_analyze_host_config(
        scene,
        pixels,
        width,
        height,
        work_width,
        0xFF,
        true,
        0.185f,
        output,
        max_detections);
}
