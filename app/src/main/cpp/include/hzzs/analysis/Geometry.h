#pragma once

#include <algorithm>

namespace hzzs::analysis {

/**
 * 使用 0.0 ~ 1.0 的归一化游戏坐标。
 *
 * 上层视觉模块应先移除状态栏、导航栏和非游戏区域，
 * 再把游戏视口内的像素坐标转换为该逻辑坐标。
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

    [[nodiscard]] bool Intersects(const RectF& other) const {
        return left < other.right && right > other.left &&
            top < other.bottom && bottom > other.top;
    }
};

inline float Clamp(float value, float min_value, float max_value) {
    return std::clamp(value, min_value, max_value);
}

inline float Clamp01(float value) {
    return Clamp(value, 0.0F, 1.0F);
}

}  // namespace hzzs::analysis
