#pragma once

/**
 * 多点找色检测器（CC-2）。
 *
 * 职责：
 * - 解析声明式多点找色规则（基色 + 相对偏移色）
 * - 在帧中寻找匹配的模式
 * - 输出标准化 Detection 协议（Kind + 归一化边界框 + Avoidance）
 *
 * 坐标约定：所有模式坐标使用视口归一化 `[0,1]`；
 * 输入帧为 ARGB 像素（`FrameView::pixels` 为 `uint32_t*`）。
 *
 * 使用方式：
 * 1. 构建 MultiColorPattern 数组（算法包 rules.json 驱动）
 * 2. 调用 find_multi_color_patterns() 在一帧中扫描所有模式
 * 3. 结果合并到 Result.detections
 *
 * 性能：
 * - 每帧最多扫描 N 个模式（N ≤ 16）
 * - 颜色容差用 diff 匹配（R/G/B 绝对值之和 ≤ threshold）
 */

#ifndef HZZS_MULTICOLOR_DETECTOR_H
#define HZZS_MULTICOLOR_DETECTOR_H

#include "vision_types.h"
#include <cstdint>
#include <vector>

namespace hzzs {

/** 单个查找点（相对于基准点的偏移 + 颜色）。 */
struct ColorPoint {
    float rel_x{0};   // 相对基准点 x 的归一化偏移
    float rel_y{0};   // 相对基准点 y 的归一化偏移
    uint8_t r{0};     // 红色通道
    uint8_t g{0};     // 绿色通道
    uint8_t b{0};     // 蓝色通道
};

/**
 * 多点找色模板。
 *
 * 一个模式 = 一个基准颜色 + 多个相对偏移颜色点。
 * 匹配成功后返回基准位置（归一化坐标），由调用方转换为边界框。
 */
struct MultiColorPattern {
    /** 基准颜色（RGB）。 */
    uint8_t base_r{0}, base_g{0}, base_b{0};
    /** 相对偏移点序列（非空）。 */
    std::vector<ColorPoint> offsets;
    /** 颜色容差阈值（0~255，diff 匹配）。 */
    float threshold{16.0f};
    /** 检测到的障碍类别。 */
    Kind kind{Kind::PLAYER};
    /** 建议规避动作。 */
    Avoidance avoidance{Avoidance::NONE};
    /** 搜索区域 top/bottom 相对视口归一化比例。 */
    float search_top_ratio{0.40f};
    float search_bottom_ratio{0.95f};
};

/**
 * 在单帧中搜索所有匹配的多点找色模式。
 *
 * @param frame ARGB 帧视图（`0xAARRGGBB`，与 Android Bitmap/JNI 一致）
 * @param patterns 找色模板列表
 * @param enabled_kind_mask 与 analyze 一致的 Kind 位掩码
 * @param global_threshold 全局颜色容差下限（取各 pattern 的 threshold 与本参数的最大值）
 * @return Result 包含所有匹配的检测（不设置 error）
 */
Result find_multi_color_patterns(
    const FrameView& frame,
    const std::vector<MultiColorPattern>& patterns,
    int enabled_kind_mask,
    float global_threshold);

}  // namespace hzzs

#endif  // HZZS_MULTICOLOR_DETECTOR_H
