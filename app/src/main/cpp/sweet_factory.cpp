#include "vision_engine.h"
#include "color_components.h"
#include "scene_geometry.h"
#include <algorithm>
#include <cmath>

namespace hzzs {
Result analyze_sweet(const FrameView& f, int work_width, int enabled_kind_mask, bool detect_player,
                     float fixed_player_x_ratio, const SceneAlgorithmParamsNative& params) {
    Result out;
    const auto ground = estimate_sweet_ground(f, params);
    const bool blocked = has_blocking_overlay(f);
    if (ground.confidence < params.ground_confidence_min) {
        out.scene_confidence = ground.confidence * .35f;
        return out;
    }

    Component player_component{};
    bool player_found = false;
    if (detect_player) {
        const auto players = player_candidates(f, work_width, ground.y, true);
        player_found = choose_player(f, players, ground.y, &player_component);
        if (player_found) {
            out.detections.push_back(
                {1, Kind::PLAYER, norm(player_component, f), .88f, false, false, Avoidance::NONE});
        }
    }
    const int player_right =
        player_found ? player_component.right : static_cast<int>(f.width * fixed_player_x_ratio);
    out.scene_confidence = detect_player
        ? std::clamp(.42f * ground.confidence + (player_found ? .58f : .08f), 0.0f, 1.0f)
        : std::clamp(.88f * ground.confidence + .12f, 0.0f, 1.0f);
    if (blocked) out.scene_confidence = std::min(out.scene_confidence, .45f);
    if ((detect_player && !player_found) || blocked) return out;

    const int stride = adaptive_stride(f, work_width);
    int hint = 100;
    const auto& colors = params.colors;
    if (kind_enabled(enabled_kind_mask, Kind::POISON_BOTTLE)) {
        auto bottles = components(
            f,
            [&](int r, int g, int b, int, int y) {
                return y > f.height * .34f && y < ground.y + f.height * .10f &&
                       g > colors.bottle_green_min &&
                       g > r * colors.bottle_green_over_red &&
                       g > b * colors.bottle_green_over_blue &&
                       r < colors.bottle_red_max;
            },
            stride,
            std::max(10, 28 / stride));
        for (const auto& c : bottles) {
            const float w = normalized_width(c, f), h = normalized_height(c, f),
                        bottom_gap = std::abs(c.bottom - ground.y) / static_cast<float>(f.height);
            if (w < params.bottle_width_min || w > params.bottle_width_max ||
                h < params.bottle_height_min || h > params.bottle_height_max || bottom_gap > .17f ||
                c.right <= player_right) {
                continue;
            }
            out.detections.push_back(
                {hint++, Kind::POISON_BOTTLE, norm(c, f), .84f, true, false, Avoidance::JUMP});
        }
    }

    const int x_stride = adaptive_stride(f, work_width);
    if (kind_enabled(enabled_kind_mask, Kind::HANGING_SPIKE)) {
        auto bows = components(
            f,
            [&](int r, int g, int b, int, int y) {
                return y > f.height * .31f && y < ground.y - f.height * .025f &&
                       r > colors.spike_red_min && b > colors.spike_blue_min &&
                       r > g * colors.spike_red_over_green && r - b < 120;
            },
            x_stride,
            std::max(14, 44 / x_stride));
        for (const auto& bow : bows) {
            const float bw = normalized_width(bow, f), bh = normalized_height(bow, f);
            if (bw < .082f || bw > .58f || bh < .035f || bh > .22f || bow.right <= player_right) continue;
            const int local_left =
                bw > .29f ? std::max(player_right, bow.right - static_cast<int>(f.width * .32f))
                          : bow.left;
            const int x1 = std::max(0, local_left - static_cast<int>(f.width * .018f));
            const int x2 = std::min(f.width, bow.right + static_cast<int>(f.width * .018f));
            int dark_hits = 0, dark_bottom = bow.bottom;
            long long dark_x_sum = 0;
            for (int y = bow.bottom; y < ground.y + f.height * .035f; y += x_stride) {
                for (int x = x1; x < x2; x += x_stride) {
                    const auto p = f.pixels[static_cast<size_t>(y) * f.width + x];
                    if (red(p) < 110 && green(p) < 105 && blue(p) < 115) {
                        ++dark_hits;
                        dark_x_sum += x;
                        dark_bottom = std::max(dark_bottom, y + x_stride);
                    }
                }
            }
            if (dark_hits < std::max(5, static_cast<int>(f.height * .006f / x_stride))) continue;
            int top = bow.top, magenta_hits = 0, local_magenta_left = x2, local_magenta_right = x1;
            for (int y = static_cast<int>(f.height * .22f); y < bow.bottom; y += x_stride) {
                for (int x = x1; x < x2; x += x_stride) {
                    const auto p = f.pixels[static_cast<size_t>(y) * f.width + x];
                    const int r = red(p), g = green(p), b = blue(p);
                    if (r > 145 && b > 82 && r > g * 1.10f && r - b < 130) {
                        top = std::min(top, y);
                        local_magenta_left = std::min(local_magenta_left, x);
                        local_magenta_right = std::max(local_magenta_right, x + x_stride);
                        ++magenta_hits;
                    }
                }
            }
            if (magenta_hits < std::max(24, static_cast<int>(f.height * .04f / x_stride))) continue;
            int fitted_left = std::max(x1, local_magenta_left - static_cast<int>(f.width * .012f));
            int fitted_right = std::min(x2, local_magenta_right + static_cast<int>(f.width * .012f));
            if (bw > .29f && dark_hits > 0) {
                const int physical_center = static_cast<int>(dark_x_sum / dark_hits);
                fitted_left = std::max(x1, physical_center - static_cast<int>(f.width * .16f));
                fitted_right = std::min(x2, physical_center + static_cast<int>(f.width * .10f));
            }
            Component c{fitted_left, top, fitted_right,
                        std::min(ground.y + static_cast<int>(f.height * .035f),
                                 dark_bottom + static_cast<int>(f.height * .018f)),
                        magenta_hits + dark_hits};
            const float w = normalized_width(c, f), h = normalized_height(c, f);
            if (w < params.spike_width_min || w > params.spike_width_max ||
                h < params.spike_height_min || h > params.spike_height_max) {
                continue;
            }
            out.detections.push_back(
                {hint++, Kind::HANGING_SPIKE, norm(c, f), .88f, true, false, Avoidance::SLIDE});
        }
    }

    if (kind_enabled(enabled_kind_mask, Kind::CAKE_STRUCTURE)) {
        auto cakes = column_regions(
            f,
            [&](int r, int g, int b) {
                return r > colors.cake_red_min && g > colors.cake_green_min &&
                       b < colors.cake_blue_max && r >= g && g > b + 10 && r - g < 105;
            },
            ground.y - static_cast<int>(f.height * .06f),
            static_cast<int>(f.height * .96f),
            x_stride,
            std::max(5, static_cast<int>(f.height * .075f) / x_stride),
            static_cast<int>(f.width * .105f),
            static_cast<int>(f.width * .018f));
        if (cakes.size() > 2) cakes.clear();
        for (const auto& c : cakes) {
            const float w = normalized_width(c, f), h = normalized_height(c, f);
            if (w < params.cake_width_min || w > params.cake_width_max || h < params.cake_height_min ||
                c.top > ground.y + f.height * .05f) {
                continue;
            }
            if (c.right <= player_right) continue;
            out.detections.push_back(
                {hint++, Kind::CAKE_STRUCTURE, norm(c, f), .79f, true, false,
                 w > params.cake_wide_width_ratio ? Avoidance::DOUBLE_JUMP : Avoidance::JUMP});
        }
    }
    return out;
}
}  // namespace hzzs
