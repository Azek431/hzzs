// 火崽崽助手（HZZS）视觉识别 — 坑位检测算法。
//
// 移植自 Python 脚本的 detect_pit()。
// 核心逻辑：
// 1. 在 y≈0.625 位置判断"奶油白地面"是否连续
// 2. 不直接找粉色坑，不直接找橙色蛋糕
// 3. 找玩家右侧第一个明显的"非地面缺口"

#pragma once

#include "VisionTypes.h"
#include <vector>
#include <utility>

namespace hzzs::vision {

/**
 * 检测坑位。
 *
 * @param rgb RGB 像素数组（宽度 * 高度 * 3，行优先）
 * @param width 图像宽度
 * @param height 图像高度
 * @param player 玩家参考框
 * @param params 检测参数
 * @param result 输出检测结果
 */
void detectPit(
    const uint8_t* rgb,
    int32_t width,
    int32_t height,
    const VisionPlayerRef& player,
    const VisionParams& params,
    VisionPitResult& result
);

}  // namespace hzzs::vision
