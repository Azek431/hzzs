#pragma once
#include "vision_types.h"
namespace hzzs {
Result analyze_sweet(const FrameView& frame, int work_width);
Result analyze_bamboo(const FrameView& frame, int work_width);
Result analyze(int scene, const FrameView& frame, int work_width);
void reset();
}
