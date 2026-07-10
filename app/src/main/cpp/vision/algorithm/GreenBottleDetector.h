// 火崽崽助手（HZZS）视觉识别 — 绿瓶检测算法。
//
// 移植自 Python 脚本的 detect_green_bottle()。
// 核心逻辑：
// 1. 在玩家中心 Y 位置的水平扫描线上找绿色像素段
// 2. 颜色判断：g > 120, g-r > 15, g-b > 30, chroma > 45
// 3. 收集连续绿色段 → 合并小间隙 → 过滤宽度 → 取最近的一个
// 4. 计算置信度：宽度越接近玩家宽度越可靠

#pragma once

#include "VisionTypes.h"
#include <vector>
#include <utility>

namespace hzzs::vision {

/**
 * 检测绿瓶。
 *
 * @param rgb RGB 像素数组（宽度 * 高度 * 3，行优先）
 * @param width 图像宽度
 * @param height 图像高度
 * @param player 玩家参考框
 * @param params 检测参数
 * @param result 输出检测结果
 */
void detectGreenBottle(
    const uint8_t* rgb,
    int32_t width,
    int32_t height,
    const VisionPlayerRef& player,
    const VisionParams& params,
    VisionGreenBottleResult& result
);

}  // namespace hzzs::vision
