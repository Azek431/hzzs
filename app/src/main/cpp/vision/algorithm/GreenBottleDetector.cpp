// 火崽崽助手（HZZS）视觉识别 — 绿瓶检测算法实现。
//
// 移植自 Python 脚本 hzzs_vision_batch_test.py 的 detect_green_bottle()。
// 逐行对照，保证结果与 Python 完全一致。

#include "GreenBottleDetector.h"

#include <algorithm>
#include <chrono>
#include <cstring>

namespace hzzs::vision {

// ============================================================
// 工具函数（对应 Python 中的辅助函数）
// ============================================================

/** clamp(value, low, high) */
inline int clamp(int value, int low, int high) {
    return value < low ? low : (value > high ? high : value);
}

/** round_int(value) */
inline int round_int(float value) {
    return static_cast<int>(value + 0.5f);
}

/** clamp_float(value, low, high) */
inline float clamp_float(float value, float low, float high) {
    return value < low ? low : (value > high ? high : value);
}

/**
 * 收集 mask[start_x..end_x] 中连续为 true 的横向片段。
 * 对应 Python 的 collect_true_segments()。
 */
static std::vector<std::pair<int, int>> collectTrueSegments(
    const char* mask, int start_x, int end_x
) {
    std::vector<std::pair<int, int>> segments;
    bool in_segment = false;
    int seg_start = start_x;

    for (int x = start_x; x <= end_x; ++x) {
        if (mask[x]) {
            if (!in_segment) {
                in_segment = true;
                seg_start = x;
            }
        } else {
            if (in_segment) {
                segments.emplace_back(seg_start, x - 1);
                in_segment = false;
            }
        }
    }
    if (in_segment) {
        segments.emplace_back(seg_start, end_x);
    }
    return segments;
}

/**
 * 合并片段之间的小缺口。
 * 对应 Python 的 merge_small_gaps()。
 */
static std::vector<std::pair<int, int>> mergeSmallGaps(
    const std::vector<std::pair<int, int>>& segments, int max_gap
) {
    if (segments.empty()) return {};

    std::vector<std::pair<int, int>> merged;
    int cur_start = segments[0].first;
    int cur_end = segments[0].second;

    for (size_t i = 1; i < segments.size(); ++i) {
        int gap = segments[i].first - cur_end - 1;
        if (gap <= max_gap) {
            cur_end = segments[i].second;
        } else {
            merged.emplace_back(cur_start, cur_end);
            cur_start = segments[i].first;
            cur_end = segments[i].second;
        }
    }
    merged.emplace_back(cur_start, cur_end);
    return merged;
}

// ============================================================
// 核心算法
// ============================================================

/**
 * 绿瓶颜色判断。
 *
 * 对应 Python 的 green_bottle_mask()。
 * RGB 严格版：g > 120, g-r > 15, g-b > 30, chroma > 45
 *
 * @param rgb RGB 像素数组（行优先，每像素 3 字节）
 * @param width 图像宽度
 * @param height 图像高度
 * @param out_mask 输出掩码（width 个 char，0 或 1）
 */
static void greenBottleMask(
    const uint8_t* rgb, int32_t width, int32_t height, char* out_mask
) {
    // 逐像素判断
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int idx = (y * width + x) * 3;
            int r = static_cast<int>(rgb[idx]);
            int g = static_cast<int>(rgb[idx + 1]);
            int b = static_cast<int>(rgb[idx + 2]);

            // chroma = max(r,g,b) - min(r,g,b)
            int max_c = std::max({r, g, b});
            int min_c = std::min({r, g, b});
            int chroma = max_c - min_c;

            out_mask[y * width + x] = (char)(
                (g > 120 &&
                (g - r) > 15 &&
                (g - b) > 30 &&
                chroma > 45) ? 1 : 0
            );
        }
    }
}

/**
 * 检测绿瓶。
 *
 * 完整流程：
 * 1. 在玩家中心 Y 位置的水平扫描线上做颜色判断
 * 2. 收集绿色连续段
 * 3. 合并小间隙
 * 4. 过滤宽度
 * 5. 取玩家右侧最近的一个
 * 6. 计算置信度
 */
void detectGreenBottle(
    const uint8_t* rgb,
    int32_t width,
    int32_t height,
    const VisionPlayerRef& player,
    const VisionParams& params,
    VisionGreenBottleResult& result
) {
    // 初始化结果
    std::memset(&result, 0, sizeof(result));
    result.found = false;
    result.edge_gap_px = 0;
    result.center_distance_px = 0;
    result.confidence = 0.0f;
    result.raw_segment_count = 0;
    result.merged_segment_count = 0;
    std::strncpy(result.message, "Bottle not found", sizeof(result.message) - 1);

    // 计时
    auto start = std::chrono::high_resolution_clock::now();

    // 1. 扫描线位置：玩家中心 Y
    int scan_y = clamp(player.center_y, 0, height - 1);

    // 2. 扫描范围：从玩家右侧稍后方开始，到右边缘前 20 像素
    int scan_start_x = clamp(
        round_int(player.right_x + player.width_px * 0.15f), 0, width - 1
    );
    int scan_end_x = clamp(width - 20, 0, width - 1);

    // 3. 全图颜色判断（生成绿色掩码）
    std::vector<char> mask(width * height, 0);
    greenBottleMask(rgb, width, height, mask.data());

    // 4. 取扫描线上的掩码
    const char* line_mask = mask.data() + scan_y * width;

    // 5. 收集连续绿色段
    auto raw_segments = collectTrueSegments(line_mask, scan_start_x, scan_end_x);

    // 6. 合并小间隙
    int merge_gap = std::max(2, round_int(player.width_px * params.green_bottle_merge_gap_factor));
    auto merged_segments = mergeSmallGaps(raw_segments, merge_gap);

    // 7. 过滤宽度
    int min_width = std::max(20, round_int(player.width_px * 0.25f));
    int max_width = std::max(min_width + 1, round_int(player.width_px * 1.50f));

    std::vector<std::pair<int, int>> candidates;
    for (auto& seg : merged_segments) {
        int w = seg.second - seg.first + 1;
        if (seg.first <= player.right_x) continue;
        if (w >= min_width && w <= max_width) {
            candidates.push_back(seg);
        }
    }

    // 8. 取玩家右侧最近的绿色段
    if (candidates.empty()) {
        auto end = std::chrono::high_resolution_clock::now();
        result.cost_ms = std::chrono::duration<double, std::milli>(end - start).count();
        return;
    }

    auto& target = *std::min_element(
        candidates.begin(), candidates.end(),
        [&](const auto& a, const auto& b) {
            return a.first - player.right_x < b.first - player.right_x;
        }
    );

    // 9. 计算 padding 和最终边界
    int padding = clamp(
        round_int(player.width_px * params.green_bottle_padding_factor), 4, 10
    );
    int left = clamp(target.first - padding, 0, width - 1);
    int right = clamp(target.second + padding, 0, width - 1);
    int center = round_int((left + right) / 2.0f);
    int w = right - left + 1;

    // 10. 计算置信度
    float width_score = 1.0f - std::min(1.0f,
        static_cast<float>(std::abs(w - player.width_px)) / static_cast<float>(std::max(1, player.width_px)));
    float confidence = clamp_float(0.75f + 0.25f * width_score, 0.0f, 1.0f);

    // 11. 填充结果
    auto end = std::chrono::high_resolution_clock::now();
    result.found = true;
    result.scan_y = scan_y;
    result.left_x = left;
    result.right_x = right;
    result.center_x = center;
    result.width_px = w;
    result.edge_gap_px = left - player.right_x;
    result.center_distance_px = center - player.center_x;
    result.confidence = confidence;
    result.cost_ms = std::chrono::duration<double, std::milli>(end - start).count();
    std::strncpy(result.message, "Bottle found", sizeof(result.message) - 1);

    // 记录片段（调试用）
    int raw_count = std::min(static_cast<int>(raw_segments.size()), 64);
    result.raw_segment_count = raw_count;
    for (int i = 0; i < raw_count; ++i) {
        result.raw_segments[i * 2] = raw_segments[i].first;
        result.raw_segments[i * 2 + 1] = raw_segments[i].second;
    }

    int merged_count = std::min(static_cast<int>(merged_segments.size()), 64);
    result.merged_segment_count = merged_count;
    for (int i = 0; i < merged_count; ++i) {
        result.merged_segments[i * 2] = merged_segments[i].first;
        result.merged_segments[i * 2 + 1] = merged_segments[i].second;
    }
}

}  // namespace hzzs::vision
