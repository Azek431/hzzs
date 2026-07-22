/**
 * 海盐客厅赛季检测（算法引擎参数驱动路径）。
 *
 * 三槽：SAND_CASTLE（地面实体）/ HANGING_ANCHOR（悬挂）/ SEA_PIT（地形口）。
 * 颜色与几何先验对齐 Python 研究版；阈值读 SceneAlgorithmParamsNative。
 * 输出归一化 Detection，与统一协议一致。
 */
#include "vision_engine.h"
#include "color_components.h"
#include "scene_geometry.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace hzzs {
namespace {

bool sea_sand(int r, int g, int b) {
    return r >= 145 && g >= 90 && g <= 225 && b >= 25 && b <= 168 &&
           (r - b) >= 42 && (g - b) >= 12;
}

bool sea_water(int r, int g, int b) {
    return b >= 80 && g >= 55 && (b - r) >= 12 && (b - g) >= 2 && r <= 175;
}

bool sea_anchor_metal(int r, int g, int b) {
    const int mx = std::max({r, g, b});
    const int mn = std::min({r, g, b});
    const int lum = (r + g + b) / 3;
    return lum >= 28 && lum <= 165 && (mx - mn) <= 62;
}

bool sea_anchor_rope(int r, int g, int b) {
    return r >= 120 && r <= 245 && g >= 80 && g <= 210 && b >= 35 && b <= 150 &&
           (r - b) >= 35 && (g - b) >= 18;
}

bool sea_ground(int r, int g, int b) {
    return r >= 185 && g >= 115 && b >= 65 && (r - g) >= 24 && (g - b) >= 18 &&
           (r - b) >= 50;
}

}  // namespace

Result analyze_sea_salt(
    const FrameView& f,
    int work_width,
    int enabled_kind_mask,
    bool detect_player,
    float fixed_player_x_ratio,
    const SceneAlgorithmParamsNative& params) {
    Result out;
    if (f.pixels == nullptr || f.width < 32 || f.height < 64) {
        out.error = "invalid frame";
        return out;
    }

    // 地面估计：在 ground_search 区间找偏暖木地板条带。
    const int y0 = static_cast<int>(f.height * params.ground_search_top);
    const int y1 = static_cast<int>(f.height * params.ground_search_bottom);
    int best_y = (y0 + y1) / 2;
    float best_score = 0.f;
    const int step = std::max(1, adaptive_stride(f, work_width));
    for (int y = y0; y < y1; y += step) {
        int hits = 0;
        int total = 0;
        for (int x = 0; x < f.width; x += step) {
            const auto p = f.pixels[static_cast<size_t>(y) * f.width + x];
            ++total;
            if (sea_ground(red(p), green(p), blue(p))) ++hits;
        }
        const float score = total > 0 ? hits / static_cast<float>(total) : 0.f;
        if (score > best_score) {
            best_score = score;
            best_y = y;
        }
    }
    if (best_score < params.ground_confidence_min * 0.55f) {
        out.scene_confidence = best_score * 0.4f;
        return out;
    }

    Component player_component{};
    bool player_found = false;
    if (detect_player) {
        const auto players = player_candidates(f, work_width, best_y, false);
        player_found = choose_player(f, players, best_y, &player_component);
        if (player_found) {
            out.detections.push_back(
                {1, Kind::PLAYER, norm(player_component, f), .88f, false, false, Avoidance::NONE});
        }
    }
    const int player_right =
        player_found ? player_component.right : static_cast<int>(f.width * fixed_player_x_ratio);
    out.scene_confidence = detect_player
        ? std::clamp(.40f * best_score + (player_found ? .55f : .10f), 0.0f, 1.0f)
        : std::clamp(.85f * best_score + .12f, 0.0f, 1.0f);
    if (detect_player && !player_found) return out;

    const int x_stride = adaptive_stride(f, work_width);
    int hint = 300;

    // SOLID: 沙堡
    if (kind_enabled(enabled_kind_mask, Kind::SAND_CASTLE)) {
        auto parts = components(
            f,
            [&](int r, int g, int b, int, int y) {
                return y > f.height * .34f && y < best_y + f.height * .06f && sea_sand(r, g, b);
            },
            x_stride,
            std::max(5, 14 / x_stride));
        auto castles = merge_components(
            std::move(parts),
            static_cast<int>(f.width * .035f),
            static_cast<int>(f.height * .055f));
        for (const auto& c : castles) {
            const float w = normalized_width(c, f);
            const float h = normalized_height(c, f);
            const float bottom_gap = std::abs(c.bottom - best_y) / static_cast<float>(f.height);
            if (w < params.statue_width_min || w > params.statue_width_max ||
                h < params.statue_height_min || h > params.statue_height_max ||
                bottom_gap > .14f || c.right <= player_right) {
                continue;
            }
            const bool tall = h > params.statue_height_min * 1.9f;
            out.detections.push_back(
                {hint++, Kind::SAND_CASTLE, norm(c, f), .84f, true, false,
                 tall ? Avoidance::DOUBLE_JUMP : Avoidance::JUMP});
        }
    }

    // HANGING: 船锚（暗金属竖条 + 绳色）
    if (kind_enabled(enabled_kind_mask, Kind::HANGING_ANCHOR)) {
        auto anchors = column_regions(
            f,
            [](int r, int g, int b) {
                return sea_anchor_metal(r, g, b) || sea_anchor_rope(r, g, b);
            },
            static_cast<int>(f.height * .18f),
            best_y + static_cast<int>(f.height * .02f),
            x_stride,
            std::max(4, static_cast<int>(f.height * .05f) / x_stride),
            static_cast<int>(f.width * .04f),
            static_cast<int>(f.width * .025f));
        for (const auto& c : anchors) {
            const float w = normalized_width(c, f);
            const float h = normalized_height(c, f);
            const float aspect = h / std::max(w, .001f);
            if (w < params.brush_width_min || w > params.brush_width_max ||
                h < params.brush_height_min || h > params.brush_height_max || aspect < 1.5f ||
                center_y(c, f) < .26f || c.right <= player_right) {
                continue;
            }
            Rect box = norm(c, f);
            box.left = std::max(0.0f, box.left - .03f);
            box.right = std::min(1.0f, box.right + .03f);
            box.top = std::max(0.0f, box.top - .02f);
            box.bottom = std::min(1.0f, box.bottom + .08f);
            out.detections.push_back(
                {hint++, Kind::HANGING_ANCHOR, box, .82f, true, false, Avoidance::SLIDE});
        }
    }

    // TERRAIN: 海坑（偏蓝水体缺口）
    if (kind_enabled(enabled_kind_mask, Kind::SEA_PIT) ||
        kind_enabled(enabled_kind_mask, Kind::PIT)) {
        const Kind pit_kind = kind_enabled(enabled_kind_mask, Kind::SEA_PIT)
            ? Kind::SEA_PIT
            : Kind::PIT;
        auto pits = column_regions(
            f,
            [](int r, int g, int b) { return sea_water(r, g, b); },
            best_y - static_cast<int>(f.height * .03f),
            static_cast<int>(f.height * .97f),
            x_stride,
            std::max(6, static_cast<int>(f.height * .08f) / x_stride),
            static_cast<int>(f.width * .12f),
            static_cast<int>(f.width * .02f));
        for (const auto& c : pits) {
            const float w = normalized_width(c, f);
            const float h = normalized_height(c, f);
            if (w < params.gap_width_min || w > params.gap_width_max || h < params.gap_height_min ||
                c.top > best_y + f.height * .07f || c.right <= player_right) {
                continue;
            }
            const float continuous_ground = horizontal_fraction(
                f,
                c.left,
                c.right,
                best_y,
                std::max(2, static_cast<int>(f.height * .006f)),
                [](int r, int g, int b) { return sea_ground(r, g, b); });
            if (continuous_ground > .36f) continue;
            out.detections.push_back(
                {hint++, pit_kind, norm(c, f), .83f, true, false,
                 w > params.gap_wide_width_ratio ? Avoidance::DOUBLE_JUMP : Avoidance::JUMP});
        }
    }

    if (out.scene_confidence < params.scene_confidence_floor && !out.detections.empty()) {
        out.scene_confidence = std::max(out.scene_confidence, params.scene_confidence_floor * 0.9f);
    }
    return out;
}

}  // namespace hzzs
