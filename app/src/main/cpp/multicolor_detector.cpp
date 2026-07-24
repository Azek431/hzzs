/**
 * 多点找色检测器实现。
 *
 * 语义对齐 AutoJS images.findMultiColors：
 * 1. 在搜索区内按步长扫基准色；
 * 2. 基准命中后校验全部相对偏移点颜色；
 * 3. 全中则输出 Detection（bounds = 基准+偏移点包络，非专用绘制层）。
 *
 * 坐标：脚本设计分辨率 1272×2772；rel_* 为设计像素/设计边长，
 * 运行时 offset_px = rel * frame_dim（与等比x/y 一致）。
 */

#include "multicolor_detector.h"
#include "vision_engine.h"

#include <algorithm>
#include <cmath>

namespace hzzs {
namespace {

inline int clamp_coord(int v, int bound) {
    return v < 0 ? 0 : (v >= bound ? bound - 1 : v);
}

/** diff 匹配：|r1-r2| + |g1-g2| + |b1-b2| <= threshold。 */
inline bool color_matches(uint8_t r1, uint8_t g1, uint8_t b1,
                          uint8_t r2, uint8_t g2, uint8_t b2,
                          int thresh) {
    const int dr = static_cast<int>(r1) - static_cast<int>(r2);
    const int dg = static_cast<int>(g1) - static_cast<int>(g2);
    const int db = static_cast<int>(b1) - static_cast<int>(b2);
    return std::abs(dr) + std::abs(dg) + std::abs(db) <= thresh;
}

/** 从 ARGB pixel（0xAARRGGBB）提取 RGB。 */
inline void extract_rgb(uint32_t argb, uint8_t& r, uint8_t& g, uint8_t& b) {
    r = static_cast<uint8_t>((argb >> 16) & 0xFF);
    g = static_cast<uint8_t>((argb >> 8) & 0xFF);
    b = static_cast<uint8_t>(argb & 0xFF);
}

/**
 * 将匹配点集（基准 + 偏移）压为归一化 bounds 并推入结果。
 * 包络比「单点膨胀」更接近障碍几何，也避免被 vision_engine 尺寸后过滤误杀。
 */
void push_multicolor_detection(
    Result& out,
    int enabled_kind_mask,
    const MultiColorPattern& pat,
    int min_x, int min_y, int max_x, int max_y,
    int width, int height,
    float confidence) {
    if (!kind_enabled(enabled_kind_mask, pat.kind)) return;
    if (width <= 0 || height <= 0) return;
    if (max_x < min_x || max_y < min_y) return;

    // 膨胀：保证最小约 3%×8% 视口，避免船锚等矮包络被后过滤误杀；非专用绘制。
    const int min_w = std::max(8, width / 32);
    const int min_h = std::max(12, height / 12);
    const int span_w = std::max(0, max_x - min_x);
    const int span_h = std::max(0, max_y - min_y);
    const int expand_x = std::max(4, (min_w - span_w + 1) / 2);
    const int expand_y = std::max(6, (min_h - span_h + 1) / 2);
    const int lx = clamp_coord(min_x - expand_x, width);
    const int ty = clamp_coord(min_y - expand_y, height);
    const int rx = clamp_coord(max_x + expand_x, width);
    const int by = clamp_coord(max_y + expand_y, height);

    const float nw = static_cast<float>(width);
    const float nh = static_cast<float>(height);
    Rect box{
        lx / nw,
        ty / nh,
        static_cast<float>(rx + 1) / nw,
        static_cast<float>(by + 1) / nh,
    };
    box.left = std::clamp(box.left, 0.0f, 1.0f);
    box.top = std::clamp(box.top, 0.0f, 1.0f);
    box.right = std::clamp(box.right, box.left, 1.0f);
    box.bottom = std::clamp(box.bottom, box.top, 1.0f);

    Detection det{};
    det.track_hint = 900;
    det.kind = pat.kind;
    det.bounds = box;
    det.confidence = std::isfinite(confidence) ? std::clamp(confidence, 0.0f, 1.0f) : 0.88f;
    det.actionable = pat.avoidance != Avoidance::NONE;
    det.diagnostic_only = false;
    det.avoidance = pat.avoidance;
    out.detections.push_back(det);
}

}  // namespace

Result find_multi_color_patterns(
    const FrameView& f,
    const std::vector<MultiColorPattern>& patterns,
    int enabled_kind_mask,
    float global_threshold) {
    Result out;
    if (f.pixels == nullptr || f.width < 32 || f.height < 64) {
        out.error = "invalid frame for multicolor detection";
        return out;
    }

    const int height = f.height;
    const int width = f.width;

    for (const auto& pat : patterns) {
        if (pat.offsets.empty() || static_cast<int>(pat.offsets.size()) > 16) continue;
        if (!kind_enabled(enabled_kind_mask, pat.kind)) continue;

        const float left_r = std::clamp(pat.search_left_ratio, 0.0f, 1.0f);
        const float right_r = std::clamp(pat.search_right_ratio, 0.0f, 1.0f);
        const float top_r = std::clamp(pat.search_top_ratio, 0.0f, 1.0f);
        const float bottom_r = std::clamp(pat.search_bottom_ratio, 0.0f, 1.0f);
        if (left_r >= right_r || top_r >= bottom_r) continue;

        const int search_x0 = clamp_coord(static_cast<int>(width * left_r), width);
        const int search_x1 = clamp_coord(static_cast<int>(width * right_r), width);
        const int search_y0 = clamp_coord(static_cast<int>(height * top_r), height);
        const int search_y1 = clamp_coord(static_cast<int>(height * bottom_r), height);
        if (search_x0 >= search_x1 || search_y0 >= search_y1) continue;

        // 容差：取 pattern 与全局中较大者（包参数可整体放宽；默认对齐酱油 10）。
        const int use_thresh = static_cast<int>(std::clamp(
            std::max({pat.threshold, global_threshold, 1.0f}),
            1.0f,
            255.0f));

        // 降采样：设计宽 1272 上约 2~3px 一步，避免全分辨率过热。
        const int x_step = std::max(1, width / 640);
        const int y_step = std::max(1, height / 360);

        // 每模板每帧最多保留一个最佳命中（偏左优先，贴近「最近障碍」）。
        bool found = false;
        int best_min_x = 0, best_min_y = 0, best_max_x = 0, best_max_y = 0;
        int best_base_x = width;

        for (int base_y = search_y0; base_y < search_y1; base_y += y_step) {
            for (int base_x = search_x0; base_x < search_x1; base_x += x_step) {
                const uint32_t base_pixel =
                    f.pixels[static_cast<size_t>(base_y) * width + base_x];
                uint8_t base_r, base_g, base_b;
                extract_rgb(base_pixel, base_r, base_g, base_b);

                if (!color_matches(base_r, base_g, base_b,
                                   pat.base_r, pat.base_g, pat.base_b,
                                   use_thresh)) {
                    continue;
                }

                int min_x = base_x;
                int min_y = base_y;
                int max_x = base_x;
                int max_y = base_y;
                bool all_match = true;
                for (const auto& cp : pat.offsets) {
                    const int ox = base_x + static_cast<int>(std::lround(cp.rel_x * width));
                    const int oy = base_y + static_cast<int>(std::lround(cp.rel_y * height));
                    if (ox < 0 || ox >= width || oy < 0 || oy >= height) {
                        all_match = false;
                        break;
                    }
                    const uint32_t offset_pixel =
                        f.pixels[static_cast<size_t>(oy) * width + ox];
                    uint8_t or_, og, ob;
                    extract_rgb(offset_pixel, or_, og, ob);
                    if (!color_matches(or_, og, ob, cp.r, cp.g, cp.b, use_thresh)) {
                        all_match = false;
                        break;
                    }
                    min_x = std::min(min_x, ox);
                    min_y = std::min(min_y, oy);
                    max_x = std::max(max_x, ox);
                    max_y = std::max(max_y, oy);
                }

                if (!all_match) continue;

                // 保留更靠左的命中（障碍更接近玩家一侧）。
                if (!found || base_x < best_base_x) {
                    found = true;
                    best_base_x = base_x;
                    best_min_x = min_x;
                    best_min_y = min_y;
                    best_max_x = max_x;
                    best_max_y = max_y;
                }
            }
        }

        if (found) {
            push_multicolor_detection(
                out,
                enabled_kind_mask,
                pat,
                best_min_x,
                best_min_y,
                best_max_x,
                best_max_y,
                width,
                height,
                0.88f);
        }
    }

    return out;
}

}  // namespace hzzs
