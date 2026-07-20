// 火崽崽助手（HZZS）视觉识别算法模块 — 数据类型定义。
//
// 对应 Python 脚本中的数据类：
// - PlayerRef → VisionPlayerRef
// - GreenBottleResult → VisionGreenBottleResult
// - PitResult → VisionPitResult
// - FrameResult → VisionFrameResult
//
// 设计原因：
// - 与 Python 脚本的数据结构保持一一对应，便于验证结果一致性
// - 所有字段都是 POD 类型，方便 JNI / ctypes 跨语言传递
// - 不依赖 STL 容器（vector/string），用 C 风格数组，方便桥接

#pragma once

#include <cstdint>
#include <cstring>

namespace hzzs::vision {

// ============================================================
// 1. 玩家参考框
// ============================================================

/**
 * 玩家参考框数据。
 *
 * 对应 Python 的 PlayerRef。
 * 由图像尺寸和固定比例计算得出，用于定位绿瓶和坑位的扫描区域。
 */
struct VisionPlayerRef {
    int32_t left_x;       // 左边界像素坐标
    int32_t right_x;      // 右边界像素坐标
    int32_t center_x;     // 中心点 X
    int32_t center_y;     // 中心点 Y
    int32_t top_y;        // 上边界 Y
    int32_t bottom_y;     // 下边界 Y
    int32_t width_px;     // 宽度（像素）
};

// ============================================================
// 2. 绿瓶检测结果
// ============================================================

/**
 * 绿瓶检测结果。
 *
 * 对应 Python 的 GreenBottleResult。
 * 包含检测到的绿色段的详细信息、置信度、耗时等。
 */
struct VisionGreenBottleResult {
    bool found;                           // 是否检测到绿瓶
    int32_t scan_y;                       // 扫描线 Y 坐标
    int32_t left_x;                       // 左边界像素坐标（-1 表示未检测）
    int32_t right_x;                      // 右边界像素坐标
    int32_t center_x;                     // 中心点 X
    int32_t width_px;                     // 宽度（像素）
    int32_t edge_gap_px;                  // 与玩家右侧的距离
    int32_t center_distance_px;           // 与玩家中心的距离
    float confidence;                     // 可信度（0.0 ~ 1.0）
    double cost_ms;                       // 耗时（毫秒）
    char message[64];                     // 结果描述（"Bottle found" / "Bottle not found"）

    // 内部字段（调试用，不对外暴露）
    int32_t raw_segments[64];             // 原始片段数量 * 2（left, right 交替）
    int32_t raw_segment_count;
    int32_t merged_segments[64];          // 合并后的片段数量 * 2
    int32_t merged_segment_count;
};

// ============================================================
// 3. 坑位检测结果
// ============================================================

/**
 * 坑位检测结果。
 *
 * 对应 Python 的 PitResult。
 * 检测玩家右侧的"奶油白地面"断口，判断是否有坑位。
 */
struct VisionPitResult {
    bool found;                           // 是否检测到坑位
    int32_t scan_y;                       // 扫描线 Y 坐标
    int32_t left_x;                       // 左边界像素坐标
    int32_t right_x;                      // 右边界像素坐标
    int32_t center_x;                     // 中心点 X
    int32_t width_px;                     // 宽度（像素）
    int32_t edge_gap_px;                  // 与玩家右侧的距离
    int32_t center_distance_px;           // 与玩家中心的距离
    float confidence;                     // 可信度（0.0 ~ 1.0）
    double cost_ms;                       // 耗时（毫秒）
    char message[64];                     // 结果描述

    // 内部字段（调试用）
    int32_t ground_segments[64];
    int32_t ground_segment_count;
    int32_t gap_segments[64];
    int32_t gap_segment_count;
};

// ============================================================
// 4. 帧检测结果汇总
// ============================================================

/**
 * 单帧检测结果汇总。
 *
 * 对应 Python 的 FrameResult。
 * 包含玩家参考框、绿瓶检测结果、坑位检测结果、总耗时等。
 */
struct VisionFrameResult {
    char file_name[256];                  // 文件名
    int32_t width;                        // 图像宽度
    int32_t height;                       // 图像高度
    double total_cost_ms;                 // 总耗时（毫秒）

    VisionPlayerRef player;               // 玩家参考框
    VisionGreenBottleResult bottle;       // 绿瓶检测结果
    VisionPitResult pit;                  // 坑位检测结果
};

// ============================================================
// 5. 检测参数（可调阈值）
// ============================================================

/**
 * 视觉检测参数。
 *
 * 对应 Python 脚本中的配置常量。
 * 可以通过 JNI / ctypes 传入，支持动态调节。
 */
struct VisionParams {
    // 玩家参考比例（归一化坐标）
    float player_left_ratio;            // 默认 108/691
    float player_right_ratio;           // 默认 214/691
    float player_center_x_ratio;        // 默认 161/691
    float player_center_y_ratio;        // 默认 894/1536
    float player_width_ratio;           // 默认 107/691
    float player_top_ratio;             // 默认 816/1536
    float player_bottom_ratio;          // 默认 971/1536

    // 绿瓶绘制上下边界（归一化）
    float bottle_top_ratio;             // 默认 802/1536
    float bottle_bottom_ratio;          // 默认 1006/1536

    // 坑位扫描线位置（归一化 Y）
    float pit_scan_y_ratio;             // 默认 0.625

    // 绿瓶检测参数
    int green_bottle_min_width;         // 最小宽度（像素），默认 20
    int green_bottle_max_width_factor;  // 最大宽度倍数，默认 1.50
    float green_bottle_merge_gap_factor;// 合并间隙因子，默认 0.25
    float green_bottle_padding_factor;  // padding 因子，默认 0.065

    // 坑位检测参数
    int pit_normal_min_width;           // 正常最小宽度，默认 36
    int pit_edge_min_width;             // 边缘最小宽度，默认 12
    float pit_max_width_factor;         // 最大宽度因子，默认 0.62
    int pit_half_height;                // 横带半高，默认 4
    float pit_ratio_min;                // 地面比例阈值，默认 0.45
    int pit_merge_gap;                  // 地面片段合并间隙，默认 6
    int pit_remove_short_min_width;     // 短片段移除阈值，默认 8
};

// 默认参数
inline VisionParams getDefaultParams() {
    return VisionParams{
        // 玩家比例
        108.0f / 691.0f,  // left
        214.0f / 691.0f,  // right
        161.0f / 691.0f,  // center_x
        894.0f / 1536.0f, // center_y
        107.0f / 691.0f,  // width
        816.0f / 1536.0f, // top
        971.0f / 1536.0f, // bottom

        // 绿瓶边界
        802.0f / 1536.0f,       // top
        1006.0f / 1536.0f,      // bottom

        // 坑位
        0.625f,                 // scan_y

        // 绿瓶检测
        20,                     // min_width
        150,                    // max_width_factor (x1.50)
        0.25f,                  // merge_gap_factor
        0.065f,                 // padding_factor

        // 坑位检测
        36,                     // normal_min_width
        12,                     // edge_min_width
        620,                    // max_width_factor (x0.62)
        4,                      // half_height
        0.45f,                  // ratio_min
        6,                      // merge_gap
        8,                      // remove_short_min_width
    };
}

}  // namespace hzzs::vision
