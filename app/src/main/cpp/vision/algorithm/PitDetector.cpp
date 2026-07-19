// 火崽崽助手（HZZS）视觉识别 — 坑位检测算法实现。
//
// 移植自 Python 脚本 hzzs_vision_batch_test.py 的 detect_pit()。
// 逐行对照，保证结果与 Python 完全一致。

#include "PitDetector.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <vector>

namespace hzzs::vision {

// ============================================================
// 工具函数
// ============================================================

inline int clamp(int value, int low, int high) {
    return value < low ? low : (value > high ? high : value);
}

inline int round_int(float value) {
    return static_cast<int>(value + 0.5f);
}

inline float clamp_float(float value, float low, float high) {
    return value < low ? low : (value > high ? high : value);
}

/**
 * 收集 mask[start_x..end_x] 中连续为 true 的横向片段。
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

/**
 * 移除宽度不足的片段。
 */
static std::vector<std::pair<int, int>> removeShortSegments(
    const std::vector<std::pair<int, int>>& segments, int min_width
) {
    std::vector<std::pair<int, int>> result;
    for (auto& seg : segments) {
        int w = seg.second - seg.first + 1;
        if (w >= min_width) {
            result.push_back(seg);
        }
    }
    return result;
}

/**
 * 从片段构建 mask（char 类型）。
 */
static std::vector<char> buildMaskFromSegments(int width, const std::vector<std::pair<int, int>>& segments) {
    std::vector<char> mask(width, 0);
    for (auto& seg : segments) {
        for (int x = seg.first; x <= seg.second && x < width; ++x) {
            mask[x] = 1;
        }
    }
    return mask;
}

// ============================================================
// 核心算法
// ============================================================

/**
 * 判断某个 x 是否属于"可站立奶油白地面"。
 *
 * 对应 Python 的 cream_ground_mask_band()。
 * 在 y±half_height 的小横带中，统计每个 x 有多少像素满足奶油白条件。
 * 如果比例 >= ratio_min，则认为该 x 是地面。
 */
static std::vector<char> creamGroundMaskBand(
    const uint8_t* rgb, int32_t width, int32_t height,
    int y, int half_height, float ratio_min
) {
    int y1 = clamp(y - half_height, 0, static_cast<int>(height) - 1);
    int y2 = clamp(y + half_height, 0, static_cast<int>(height) - 1);

    std::vector<char> result(width, 0);

    // 对每个 x，统计横带中满足条件的像素比例
    for (int x = 0; x < width; ++x) {
        int true_count = 0;
        int total = 0;

        for (int py = y1; py <= y2; ++py) {
            int idx = (py * width + x) * 3;
            int r = static_cast<int>(rgb[idx]);
            int g = static_cast<int>(rgb[idx + 1]);
            int b = static_cast<int>(rgb[idx + 2]);

            int max_c = std::max({r, g, b});
            int min_c = std::min({r, g, b});
            int chroma = max_c - min_c;

            // 奶油白/平台高亮区域：亮、低饱和、不要太偏色
            if (r > 220 && g > 210 && b > 188 && chroma < 78) {
                true_count++;
            }
            total++;
        }

        float ratio = static_cast<float>(true_count) / static_cast<float>(total);
        result[x] = (char)(ratio >= ratio_min ? 1 : 0);
    }

    return result;
}

/**
 * 检测坑位。
 *
 * 完整流程：
 * 1. 在 y≈0.625 位置判断"奶油白地面"是否连续
 * 2. 收集地面片段 → 合并小间隙 → 移除短片段
 * 3. 反转 mask 得到缺口片段
 * 4. 过滤缺口宽度 → 取玩家右侧最近的
 * 5. 计算置信度
 */
void detectPit(
    const uint8_t* rgb,
    int32_t width,
    int32_t height,
    const VisionPlayerRef& player,
    const VisionParams& params,
    VisionPitResult& result
) {
    // 初始化结果
    std::memset(&result, 0, sizeof(result));
    result.found = false;
    result.edge_gap_px = 0;
    result.center_distance_px = 0;
    result.confidence = 0.0f;
    result.ground_segment_count = 0;
    result.gap_segment_count = 0;
    std::strncpy(result.message, "Pit not found", sizeof(result.message) - 1);

    // 计时
    auto start = std::chrono::high_resolution_clock::now();

    // 1. 扫描线位置：y ≈ 0.625 * height
    int scan_y = clamp(
        round_int(static_cast<float>(height) * params.pit_scan_y_ratio),
        0, static_cast<int>(height) - 1
    );

    // 2. 扫描范围：从玩家右侧稍后方开始
    int scan_start_x = clamp(
        round_int(player.right_x + player.width_px * 0.15f), 0, width - 1
    );
    int scan_end_x = width - 1;

    // 3. 判断"奶油白地面"横带
    auto ground_mask = creamGroundMaskBand(
        rgb, width, height,
        scan_y,
        params.pit_half_height,
        params.pit_ratio_min
    );

    // 4. 收集地面片段
    auto raw_ground_segments = collectTrueSegments(
        ground_mask.data(), scan_start_x, scan_end_x
    );

    // 5. 合并小间隙
    auto merged_ground_segments = mergeSmallGaps(raw_ground_segments, params.pit_merge_gap);

    // 6. 移除短片段
    merged_ground_segments = removeShortSegments(merged_ground_segments, params.pit_remove_short_min_width);

    // 7. 构建平滑地面 mask
    auto smoothed_ground_mask = buildMaskFromSegments(width, merged_ground_segments);
    // 8. 缺口 = 非地面。旧实现误把地面片段再次当成缺口片段。
    auto gap_mask = smoothed_ground_mask;
    for (auto& value : gap_mask) value = value ? 0 : 1;
    auto gap_segments = collectTrueSegments(
        gap_mask.data(), scan_start_x, scan_end_x
    );

    // 9. 过滤缺口，收集候选
    int normal_min_width = std::max(36, round_int(width * 0.06f));
    int edge_min_width = std::max(12, round_int(width * 0.02f));
    int max_width = round_int(width * 0.62f);

    struct Candidate {
        int left, right;
        float confidence;
    };
    std::vector<Candidate> candidates;

    for (auto& seg : gap_segments) {
        int gap_width = seg.second - seg.first + 1;
        if (seg.first <= player.right_x) continue;
        if (gap_width > max_width) continue;

        bool edge_enter = seg.second >= width - 2;

        if (edge_enter) {
            if (gap_width < edge_min_width) continue;
        } else {
            if (gap_width < normal_min_width) continue;
        }

        // 左侧地面评分
        int before_start = std::max(scan_start_x, seg.first - 90);
        int before_count = 0;
        for (int x = before_start; x < seg.first; ++x) {
            if (x < width && smoothed_ground_mask[x]) before_count++;
        }
        float before_score = static_cast<float>(before_count) / std::max(1, seg.first - before_start);

        if (before_score < 0.20f) continue;

        // 右侧地面评分
        float after_score = 0.0f;
        if (!edge_enter) {
            int after_end = std::min(width - 1, seg.second + 90);
            int after_count = 0;
            for (int x = seg.second + 1; x <= after_end; ++x) {
                if (x < width && smoothed_ground_mask[x]) after_count++;
            }
            int after_range = after_end - seg.second;
            after_score = static_cast<float>(after_count) / std::max(1, after_range);
        }

        // 置信度
        float width_score = std::min(1.0f, static_cast<float>(gap_width) / std::max(1.0f, static_cast<float>(width) * 0.25f));
        float confidence = 0.35f * before_score +
                          0.35f * (edge_enter ? 0.80f : after_score) +
                          0.30f * width_score;
        confidence = clamp_float(confidence, 0.0f, 1.0f);

        candidates.push_back({seg.first, seg.second, confidence});
    }

    // 10. 取玩家右侧最近的坑位
    if (candidates.empty()) {
        auto end = std::chrono::high_resolution_clock::now();
        result.cost_ms = std::chrono::duration<double, std::milli>(end - start).count();
        return;
    }

    auto& target = *std::min_element(
        candidates.begin(), candidates.end(),
        [&player](const Candidate& a, const Candidate& b) -> bool {
            return a.left - player.right_x < b.left - player.right_x;
        }
    );

    // 11. 填充结果
    auto end = std::chrono::high_resolution_clock::now();
    int left = target.left;
    int right = target.right;
    int center = round_int((left + right) / 2.0f);
    int w = right - left + 1;

    result.found = true;
    result.scan_y = scan_y;
    result.left_x = left;
    result.right_x = right;
    result.center_x = center;
    result.width_px = w;
    result.edge_gap_px = left - player.right_x;
    result.center_distance_px = center - player.center_x;
    result.confidence = target.confidence;
    result.cost_ms = std::chrono::duration<double, std::milli>(end - start).count();
    std::strncpy(result.message, "Pit found", sizeof(result.message) - 1);

    // 记录片段（调试用）
    int ground_count = std::min(static_cast<int>(merged_ground_segments.size()), 64);
    result.ground_segment_count = ground_count;
    for (int i = 0; i < ground_count; ++i) {
        result.ground_segments[i * 2] = merged_ground_segments[i].first;
        result.ground_segments[i * 2 + 1] = merged_ground_segments[i].second;
    }

    int gap_count = std::min(static_cast<int>(gap_segments.size()), 64);
    result.gap_segment_count = gap_count;
    for (int i = 0; i < gap_count; ++i) {
        result.gap_segments[i * 2] = gap_segments[i].first;
        result.gap_segments[i * 2 + 1] = gap_segments[i].second;
    }
}

}  // namespace hzzs::vision
