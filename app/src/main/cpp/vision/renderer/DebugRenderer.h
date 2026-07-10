#ifndef HZZS_VISION_RENDERER_H
#define HZZS_VISION_RENDERER_H

#include "../algorithm/VisionTypes.h"

namespace hzzs::vision {

void renderDebugImage(
    const uint8_t* rgba_input,
    const uint8_t* rgb_input,
    int32_t width,
    int32_t height,
    const VisionFrameResult* result,
    uint8_t* rgba_output
);

}  // namespace hzzs::vision

#endif  // HZZS_VISION_RENDERER_H
