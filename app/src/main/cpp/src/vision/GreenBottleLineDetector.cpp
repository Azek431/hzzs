// 火崽崽助手（HZZS）绿瓶单行扫描检测器 — 实现。
//
// 核心算法：
// 1. 计算扫描线 Y = playerCenterY（归一化 → 像素）
// 2. 计算扫描范围：
//    - startX = playerRight + playerWidth * 0.15
//    - endX = screenWidth - 20
// 3. 沿扫描线逐像素采样，判断 RGB 色差条件
// 4. 聚合连续绿色像素为片段
// 5. 合并相邻片段、过滤窄片段
// 6. 输出最佳检测结果

#include "hzzs/vision/GreenBottleLineDetector.h"

#include <algorithm>
#include <android/log.h>
#include <chrono>
#include <cmath>

namespace hzzs::vision {

namespace {

/** 日志标签，用于 Android logcat 中过滤视觉模块日志 */
constexpr const char* kLogTag = "HZZS-Vision";

/**
 * 从 ARGB 像素值中提取 R 通道（0~255）。
 *
 * ARGB 格式：0xAARRGGBB
 * R 通道位于低 8 位。
 */
inline int ExtractR(std::int32_t pixel) {
    return static_cast<int>(pixel & 0xFF);
}

/**
 * 从 ARGB 像素值中提取 G 通道（0~255）。
 *
 * G 通道位于低 16~23 位。
 */
inline int ExtractG(std::int32_t pixel) {
    return static_cast<int>((pixel >> 8) & 0xFF);
}

/**
 * 从 ARGB 像素值中提取 B 通道（0~255）。
 *
 * B 通道位于低 24~31 位。
 */
inline int ExtractB(std::int32_t pixel) {
    return static_cast<int>((pixel >> 16) & 0xFF);
}

/** 返回三个整数中的最大值 */
inline int Max3(int a, int b, int c) {
    return (a >= b) ? ((a >= c) ? a : c) : ((b >= c) ? b : c);
}

/** 返回三个整数中的最小值 */
inline int Min3(int a, int b, int c) {
    return (a <= b) ? ((a <= c) ? a : c) : ((b <= c) ? b : c);
}

}  // namespace

// ==================== 构造函数 ====================

GreenBottleLineDetector::GreenBottleLineDetector() = default;

// ==================== IsGreenPixel ====================

bool GreenBottleLineDetector::IsGreenPixel(std::int32_t pixel) const {
    int r = ExtractR(pixel);
    int g = ExtractG(pixel);
    int b = ExtractB(pixel);

    // 色差条件：
    // 1. G > 120：绿色通道足够亮，排除暗色区域
    // 2. G - R > 15：绿色明显强于红色，排除偏黄/白色区域
    // 3. G - B > 30：绿色明显强于蓝色，排除青色/蓝色区域
    // 4. max-min > 45：色彩饱和度高，排除灰色/低饱和绿色
    return (
        g > 120 &&
        (g - r) > 15 &&
        (g - b) > 30 &&
        (Max3(r, g, b) - Min3(r, g, b)) > 45
    );
}

// ==================== MergeSegments ====================

std::vector<std::pair<std::int32_t, std::int32_t>>
GreenBottleLineDetector::MergeSegments(
    std::vector<std::pair<std::int32_t, std::int32_t>> segments,
    std::int32_t max_gap
) const {
    // 空列表直接返回
    if (segments.empty()) return segments;

    // 已按 startX 排序（Scan 中保证），从左到右合并相邻片段
    std::vector<std::pair<std::int32_t, std::int32_t>> merged;
    merged.reserve(segments.size());

    std::int32_t cur_left = segments[0].first;
    std::int32_t cur_right = segments[0].second;

    for (size_t i = 1; i < segments.size(); ++i) {
        std::int32_t next_left = segments[i].first;
        std::int32_t next_right = segments[i].second;

        // 间隙不超过 max_gap → 合并
        std::int32_t gap = next_left - cur_right;
        if (gap <= 0 || gap <= max_gap) {
            // 更新右边界
            cur_right = std::max(cur_right, next_right);
        } else {
            // 保存当前片段，开始新片段
            merged.emplace_back(cur_left, cur_right);
            cur_left = next_left;
            cur_right = next_right;
        }
    }
    // 保存最后一个片段
    merged.emplace_back(cur_left, cur_right);

    return merged;
}

// ==================== Scan ====================

hzzs::vision::GreenBottleResult
hzzs::vision::GreenBottleLineDetector::Scan(
    const std::int32_t* pixels,
    std::int32_t width,
    std::int32_t height,
    float player_left,
    float player_top,
    float player_right,
    float player_bottom
) {
    auto start = std::chrono::steady_clock::now();

    GreenBottleResult result{};
    result.scan_y = 0;

    // 边界检查
    if (pixels == nullptr || width <= 0 || height <= 0) {
        return result;
    }

    // ========== 1. 计算玩家参数（像素坐标） ==========
    float player_center_y = (player_top + player_bottom) * 0.5F;
    float player_width = player_right - player_left;

    // 扫描线 Y（像素）
    std::int32_t scan_y = static_cast<std::int32_t>(player_center_y * height + 0.5F);
    if (scan_y < 0) scan_y = 0;
    if (scan_y >= height) scan_y = height - 1;
    result.scan_y = scan_y;

    // 扫描起点 X（像素）：playerRight + playerWidth * 0.15
    std::int32_t start_x = static_cast<std::int32_t>(
        (player_right + player_width * kScanStartOffset) * width + 0.5F
    );
    // 扫描终点 X（像素）：width - 20
    std::int32_t end_x = width - kScanEndPaddingPx;
    if (end_x >= width) end_x = width - 1;

    // 确保起点 < 终点
    if (start_x >= end_x) {
        start_x = 0;
        end_x = width - 1;
    }

    // 计算动态参数
    std::int32_t player_width_px = static_cast<std::int32_t>(player_width * width + 0.5F);
    std::int32_t max_gap = static_cast<std::int32_t>(player_width_px * kMaxGapRatio);
    if (max_gap < kMinWidthAbsolute) max_gap = kMinWidthAbsolute;

    std::int32_t min_width = static_cast<std::int32_t>(player_width_px * 0.25F);
    if (min_width < kMinWidthAbsolute) min_width = kMinWidthAbsolute;

    std::int32_t max_width = static_cast<std::int32_t>(player_width_px * kMaxWidthRatio);

    // padding：playerWidth * 0.065，限制在 4~10 像素
    std::int32_t padding = static_cast<std::int32_t>(player_width_px * kPaddingRatio);
    if (padding < kPaddingMinPx) padding = kPaddingMinPx;
    if (padding > kPaddingMaxPx) padding = kPaddingMaxPx;

    // ========== 2. 沿扫描线逐像素采样，收集绿色片段 ==========
    std::vector<std::pair<std::int32_t, std::int32_t>> raw_segments;
    int raw_count = 0;

    // 状态机：正在扫描绿色区域 / 空闲
    bool in_green = false;
    std::int32_t segment_start = -1;

    for (int x = start_x; x <= end_x; ++x) {
        std::int32_t pixel = pixels[scan_y * width + x];

        if (IsGreenPixel(pixel)) {
            if (!in_green) {
                // 开始新片段
                in_green = true;
                segment_start = x;
            }
        } else {
            if (in_green) {
                // 结束当前片段
                in_green = false;
                std::int32_t segment_end = x - 1;

                // 记录原始片段
                raw_segments.emplace_back(segment_start, segment_end);
                ++raw_count;

                // 过滤：宽度不足的直接丢弃
                std::int32_t seg_width = segment_end - segment_start + 1;
                if (seg_width < min_width) {
                    raw_segments.pop_back();
                }
            }
        }
    }
    // 处理扫描线末尾仍在绿色区域的情况
    if (in_green && segment_start >= 0) {
        std::int32_t segment_end = end_x;
        raw_segments.emplace_back(segment_start, segment_end);
        ++raw_count;
        std::int32_t seg_width = segment_end - segment_start + 1;
        if (seg_width < min_width) {
            raw_segments.pop_back();
        }
    }

    result.raw_segment_count = raw_count;

    // ========== 3. 合并相邻片段 ==========
    std::vector<std::pair<std::int32_t, std::int32_t>> merged = MergeSegments(std::move(raw_segments), padding);
    result.merged_segment_count = static_cast<int>(merged.size());

    // 没有符合条件的片段 → 未检测到
    if (merged.empty()) {
        auto end = std::chrono::steady_clock::now();
        result.cost_ms = std::chrono::duration<float, std::milli>(end - start).count();
        return result;
    }

    // ========== 4. 选择最佳片段（最宽的通常是最可靠的） ==========
    std::int32_t best_left = merged[0].first;
    std::int32_t best_right = merged[0].second;

    for (size_t i = 1; i < merged.size(); ++i) {
        std::int32_t w = merged[i].second - merged[i].first + 1;
        std::int32_t best_w = best_right - best_left + 1;
        if (w > best_w) {
            best_left = merged[i].first;
            best_right = merged[i].second;
        }
    }

    // 过滤：最佳片段宽度超过 max_width 可能是误检
    std::int32_t best_width = best_right - best_left + 1;
    if (best_width > max_width) {
        auto end = std::chrono::steady_clock::now();
        result.cost_ms = std::chrono::duration<float, std::milli>(end - start).count();
        return result;
    }

    // ========== 5. 计算输出字段 ==========
    std::int32_t center_x = (best_left + best_right) / 2;
    std::int32_t player_right_px = static_cast<std::int32_t>(player_right * width + 0.5F);

    result.left_x = best_left;
    result.right_x = best_right;
    result.center_x = center_x;
    result.edge_gap_px = best_left - player_right_px;
    result.center_distance_px = center_x - player_right_px;

    // 归一化坐标
    result.left_ratio = static_cast<float>(best_left) / static_cast<float>(width);
    result.right_ratio = static_cast<float>(best_right) / static_cast<float>(width);
    result.center_x_ratio = static_cast<float>(center_x) / static_cast<float>(width);
    result.edge_gap_ratio = static_cast<float>(result.edge_gap_px) / static_cast<float>(width);

    result.found = true;

    // ========== 6. 计算置信度 ==========
    // 置信度因素：
    // - 片段宽度适中：越接近 playerWidth 越可信
    // - 边缘距离合理：不要太近（< playerWidth*0.1）也不要太远（> width*0.5）
    // - 片段数量少：单个大片段比多个小片段更可信
    float width_score = 1.0F - std::abs(best_width - player_width_px) /
                                    static_cast<float>(std::max(player_width_px, 1));
    width_score = std::max(0.0F, std::min(1.0F, width_score));

    float dist_ratio = static_cast<float>(result.edge_gap_px) /
                       static_cast<float>(std::max(width - player_right_px, 1));
    float dist_score = 1.0F - std::min(dist_ratio, 1.0F);

    float segment_score = merged.size() == 1 ? 0.9F : 0.7F;

    result.confidence = width_score * 0.5F + dist_score * 0.2F + segment_score * 0.3F;

    // 耗时
    auto end = std::chrono::steady_clock::now();
    result.cost_ms = std::chrono::duration<float, std::milli>(end - start).count();

    __android_log_print(
        ANDROID_LOG_DEBUG,
        kLogTag,
        "[GreenBottle] found=%d scanY=%d left=%d right=%d center=%d gap=%d "
        "confidence=%.3f cost=%.2fms rawSeg=%d mergeSeg=%d",
        result.found ? 1 : 0,
        result.scan_y,
        result.left_x,
        result.right_x,
        result.center_x,
        result.edge_gap_px,
        result.confidence,
        result.cost_ms,
        result.raw_segment_count,
        result.merged_segment_count
    );

    return result;
}

}  // namespace hzzs::vision
