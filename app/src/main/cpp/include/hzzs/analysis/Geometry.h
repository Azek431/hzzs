#pragma once

#include <algorithm>

namespace hzzs::analysis {

/**
 * 统一使用 0.0 ~ 1.0 的逻辑坐标。
 * 视觉层后续应先把真实像素坐标转换为逻辑坐标，再交给算法层。
 */
struct RectF {
    float left{0.0F};
    float top{0.0F};
    float right{0.0F};
    float bottom{0.0F};

    [[nodiscard]] float Width() const {
        return right - left;
    }

    [[nodiscard]] float Height() const {
        return bottom - top;
    }

    [[nodiscard]] float CenterX() const {
        return (left + right) * 0.5F;
    }

    [[nodiscard]] float CenterY() const {
        return (top + bottom) * 0.5F;
    }

    [[nodiscard]] bool IsValid() const {
        return right > left && bottom > top;
    }
};

inline float Clamp01(float value) {
    return std::clamp(value, 0.0F, 1.0F);
}

}  // namespace hzzs::analysis
