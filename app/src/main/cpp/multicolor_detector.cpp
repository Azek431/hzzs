/**
 * 多点找色检测器实现。
 *
 * 核心算法：
 * 1. 对每个 pattern，在搜索区域内逐像素匹配基准颜色
 * 2. 若基准匹配成功，检查所有相对偏移点是否也匹配对应颜色
 * 3. 全部匹配 → 记录基准位置，转换为归一化边界框 + Kind
 *
 * 坐标转换：脚本中给出的找色点是相对视口比例（57/1272, 86/2772），
 * 这里直接用帧宽高做缩放：offset_px = rel_ratio * frame_dim。
 */

#include "multicolor_detector.h"
#include "vision_engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace hzzs {
namespace {

inline int clamp_coord(int v, int bound) {
    return v < 0 ? 0 : (v >= bound ? bound - 1 : v);
}

/**
 * diff 匹配：|r1-r2| + |g1-g2| + |b1-b2| <= threshold。
 */
inline bool color_matches(uint8_t r1, uint8_t g1, uint8_t b1,
                          uint8_t r2, uint8_t g2, uint8_t b2,
                          int thresh) {
    const int dr = static_cast<int>(r1) - static_cast<int>(r2);
    const int dg = static_cast<int>(g1) - static_cast<int>(g2);
    const int db = static_cast<int>(b1) - static_cast<int>(b2);
    const int dist = std::abs(dr) + std::abs(dg) + std::abs(db);
    return dist <= thresh;
}

/** 从 ARGB pixel（0xAARRGGBB，与 color_components.h 一致）提取 RGB。 */
inline void extract_rgb(uint32_t argb, uint8_t& r, uint8_t& g, uint8_t& b) {
    r = static_cast<uint8_t>((argb >> 16) & 0xFF);
    g = static_cast<uint8_t>((argb >> 8) & 0xFF);
    b = static_cast<uint8_t>(argb & 0xFF);
}

/** 将模式匹配的基准像素坐标 → 归一化包围盒（膨胀一圈作为容差）。 */
void push_multicolor_detection(
    Result& out,
    int enabled_kind_mask,
    const MultiColorPattern& pat,
    int base_px_x, int base_px_y,
    int width, int height,
    float confidence) {
    if (!kind_enabled(enabled_kind_mask, pat.kind)) return;

    // 膨胀容差作为边界框（~4% 视口宽度）
    const int expand = std::max(4, width / 40);
    const int lx = clamp_coord(base_px_x - expand, width);
    const int ty = clamp_coord(base_px_y - expand, height);
    const int rx = clamp_coord(base_px_x + expand, width);
    const int by = clamp_coord(base_px_y + expand, height);

    // 归一化
    const float nw = static_cast<float>(width);
    const float nh = static_cast<float>(height);
    Rect box{
        lx / nw, ty / nh,
        static_cast<float>(rx + 1) / nw, static_cast<float>(by + 1) / nh,
    };
    box.left = std::clamp(box.left, 0.0f, 1.0f);
    box.top = std::clamp(box.top, 0.0f, 1.0f);
    box.right = std::clamp(box.right, box.left, 1.0f);
    box.bottom = std::clamp(box.bottom, box.top, 1.0f);

    Detection det{};
    det.track_hint = 900;  // 预留标记
    det.kind = pat.kind;
    det.bounds = box;
    det.confidence = std::isfinite(confidence) ? std::clamp(confidence, 0.0f, 1.0f) : 0.85f;
    det.actionable = true;
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
    const int thresh = static_cast<int>(std::max(global_threshold, 1.0f));

    // 跳过 PLAYER（0 索引不检测）
    const size_t skip = (enabled_kind_mask & 1) ? 0 : 1;

    for (const auto& pat : patterns) {
        if (static_cast<int>(pat.offsets.size()) > 16) continue;
        if (!kind_enabled(enabled_kind_mask, pat.kind)) continue;

        const int search_y0 = clamp_coord(static_cast<int>(height * pat.search_top_ratio), height);
        const int search_y1 = clamp_coord(static_cast<int>(height * pat.search_bottom_ratio), height);
        if (search_y0 >= search_y1) continue;

        const int pat_thresh = pat.threshold > 0.0f
            ? static_cast<int>(std::max(global_threshold, pat.threshold))
            : thresh;

        // 双循环扫描基准色
        // stride 降采样以提升性能
        const int x_step = std::max(1, width / 512);
        const int y_step = std::max(1, height / 288);

        for (int base_y = search_y0; base_y < search_y1; base_y += y_step) {
            for (int base_x = 0; base_x < width; base_x += x_step) {
                const uint32_t base_pixel =
                    f.pixels[static_cast<size_t>(base_y) * width + base_x];
                uint8_t base_r, base_g, base_b;
                extract_rgb(base_pixel, base_r, base_g, base_b);

                if (!color_matches(base_r, base_g, base_b,
                                   pat.base_r, pat.base_g, pat.base_b,
                                   pat_thresh)) {
                    continue;
                }

                // 基准色匹配 → 检查偏移点
                bool all_match = true;
                for (size_t i = 0; i < pat.offsets.size(); ++i) {
                    const auto& cp = pat.offsets[i];
                    // rel 是视口归一化偏移 → 转像素偏移
                    const int ox = base_x + static_cast<int>(cp.rel_x * width);
                    const int oy = base_y + static_cast<int>(cp.rel_y * height);

                    if (ox < 0 || ox >= width || oy < 0 || oy >= height) {
                        all_match = false;
                        break;
                    }

                    const uint32_t offset_pixel =
                        f.pixels[static_cast<size_t>(oy) * width + ox];
                    uint8_t or_, og, ob;
                    extract_rgb(offset_pixel, or_, og, ob);

                    if (!color_matches(or_, og, ob, cp.r, cp.g, cp.b, pat_thresh)) {
                        all_match = false;
                        break;
                    }
                }

                if (all_match) {
                    push_multicolor_detection(out, enabled_kind_mask, pat,
                                              base_x, base_y, width, height, 0.88f);
                }
            }
        }
    }

    return out;
}

}  // namespace hzzs
