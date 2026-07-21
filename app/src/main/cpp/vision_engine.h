#pragma once
#include "algorithm_runtime.h"
#include "vision_types.h"

namespace hzzs {

Result analyze_sweet(const FrameView& frame, int work_width, int enabled_kind_mask,
                     bool detect_player, float fixed_player_x_ratio,
                     const SceneAlgorithmParamsNative& params);
Result analyze_bamboo(const FrameView& frame, int work_width, int enabled_kind_mask,
                      bool detect_player, float fixed_player_x_ratio,
                      const SceneAlgorithmParamsNative& params);

/** 使用当前 AlgorithmRuntime 快照分析；不在帧路径解析 JSON。 */
Result analyze(int scene, const FrameView& frame, int work_width, int enabled_kind_mask,
               bool detect_player, float fixed_player_x_ratio);

/** 显式传入 profile（宿主机/测试用）。 */
Result analyze_with_profile(int scene, const FrameView& frame, int work_width, int enabled_kind_mask,
                            bool detect_player, float fixed_player_x_ratio,
                            const AlgorithmRuntimeProfileNative& profile);

void reset();

inline bool kind_enabled(int mask, Kind kind) {
    const int bit = static_cast<int>(kind);
    return bit >= 0 && bit < 31 && (mask & (1 << bit)) != 0;
}

}  // namespace hzzs
