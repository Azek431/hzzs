#pragma once

#include <algorithm>

namespace hzzs::analysis {

/**
 * 使用 0.0 ~ 1.0 的归一化游戏坐标。
 *
 * 上层视觉模块应先移除状态栏、导航栏和非游戏区域，
 * 再把游戏视口内的像素坐标转换为该逻辑坐标。
 *
 * 所有碰撞检测、ETA 计算、角色追踪均基于此归一化坐标系，
 * 不依赖具体设备分辨率。
 *
 * 成员变量：
 * - left/top/right/bottom：矩形四边在归一化坐标中的位置
 * - Width()/Height()：返回矩形的宽度和高度（均为正数）
 * - CenterX()/CenterY()：返回矩形中心点坐标
 * - IsValid()：校验 right > left 且 bottom > top
 * - Intersects()：判断是否与另一个矩形重叠
 *
 * 注意：此结构体不包含任何业务逻辑，纯几何工具。
 * 所有方法均为 const，不修改对象状态。
 */
struct RectF {
    float left{0.0F};
    float top{0.0F};
    float right{0.0F};
    float bottom{0.0F};

    /** 返回矩形宽度（right - left），假设矩形有效 */
    [[nodiscard]] float Width() const {
        return right - left;
    }

    /** 返回矩形高度（bottom - top），假设矩形有效 */
    [[nodiscard]] float Height() const {
        return bottom - top;
    }

    /** 返回矩形中心点的 X 坐标 */
    [[nodiscard]] float CenterX() const {
        return (left + right) * 0.5F;
    }

    /** 返回矩形中心点的 Y 坐标 */
    [[nodiscard]] float CenterY() const {
        return (top + bottom) * 0.5F;
    }

    /**
     * 校验矩形是否有效。
     *
     * 有效条件：right > left 且 bottom > top。
     * 如果矩形无效（如坐标未初始化或被污染），此方法返回 false，
     * 调用方应跳过基于此矩形的计算。
     */
    [[nodiscard]] bool IsValid() const {
        return right > left && bottom > top;
    }

    /**
     * 判断当前矩形是否与另一个矩形相交（重叠）。
     *
     * 使用标准 AABB 相交检测：
     * - 当前矩形左边界 < 对方右边界
     * - 当前矩形右边界 > 对方左边界
     * - 当前矩形上边界 < 对方下边界
     * - 当前矩形下边界 > 对方上边界
     *
     * @param other 待检测的另一个矩形
     * @return true 如果两个矩形有重叠区域
     */
    [[nodiscard]] bool Intersects(const RectF& other) const {
        return left < other.right && right > other.left &&
            top < other.bottom && bottom > other.top;
    }
};

/**
 * 将浮点数限制在 [min_value, max_value] 范围内。
 *
 * 这是 std::clamp 的封装，提供统一的边界约束工具。
 *
 * @param value 待限制的数值
 * @param min_value 最小允许值
 * @param max_value 最大允许值
 * @return 限制后的值
 */
inline float Clamp(float value, float min_value, float max_value) {
    return std::clamp(value, min_value, max_value);
}

/**
 * 将浮点数限制在 [0.0, 1.0] 范围内。
 *
 * 专门用于归一化坐标的边界约束，确保值不会超出合法范围。
 *
 * @param value 待限制的归一化值
 * @return 限制在 [0.0, 1.0] 范围内的值
 */
inline float Clamp01(float value) {
    return Clamp(value, 0.0F, 1.0F);
}

}  // namespace hzzs::analysis
