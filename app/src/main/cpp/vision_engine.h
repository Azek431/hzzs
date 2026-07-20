#pragma once
#include "vision_types.h"
namespace hzzs {
Result analyze_sweet(const FrameView& frame, int work_width, int enabled_kind_mask,
                     bool detect_player, float fixed_player_x_ratio);
Result analyze_bamboo(const FrameView& frame, int work_width, int enabled_kind_mask,
                      bool detect_player, float fixed_player_x_ratio);
Result analyze(int scene, const FrameView& frame, int work_width, int enabled_kind_mask,
               bool detect_player, float fixed_player_x_ratio);
void reset();
inline bool kind_enabled(int mask, Kind kind) {
    const int bit = static_cast<int>(kind);
    return bit >= 0 && bit < 31 && (mask & (1 << bit)) != 0;
}
}
