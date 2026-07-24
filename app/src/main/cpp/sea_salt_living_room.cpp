/**
 * 海盐客厅赛季检测（算法引擎参数驱动路径）。
 *
 * 三槽：SAND_CASTLE（地面实体）/ HANGING_ANCHOR（悬挂）/ SEA_PIT（地形口）。
 * 颜色对齐研究版；尺寸用海盐专用比例窗（不再误用雕像/毛笔阈值）。
 * 玩家检测失败时仍用固定 X 继续扫障碍，避免整帧空结果。
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
    // 研究版 sea_sand_castle + 略放宽暗边/高光。
    if (r >= 145 && g >= 90 && g <= 225 && b >= 25 && b <= 168 &&
        (r - b) >= 42 && (g - b) >= 12) {
        return true;
    }
    // 沙堡阴影面：偏暗但仍暖黄。
    return r >= 120 && g >= 75 && g <= 200 && b <= 150 &&
           (r - b) >= 30 && (g - b) >= 8 && r >= g - 10;
}

bool sea_water(int r, int g, int b) {
    // 研究版 sea_water_pit + 放宽青/灰/深水。
    if (b >= 80 && g >= 55 && (b - r) >= 12 && (b - g) >= 2 && r <= 175) {
        return true;
    }
    if (b >= 70 && g >= 50 && b >= r + 8 && b >= g && r <= 190 && g <= 200) {
        return true;
    }
    // 深水/阴影水面：偏暗但 B 仍主导。
    if (b >= 55 && b >= r + 5 && b >= g - 5 && r <= 140 && g <= 150 && (r + g + b) < 360) {
        return true;
    }
    return false;
}

bool sea_anchor_metal(int r, int g, int b) {
    const int mx = std::max({r, g, b});
    const int mn = std::min({r, g, b});
    const int lum = (r + g + b) / 3;
    return lum >= 28 && lum <= 175 && (mx - mn) <= 70;
}

bool sea_anchor_rope(int r, int g, int b) {
    return r >= 110 && r <= 245 && g >= 70 && g <= 210 && b >= 30 && b <= 160 &&
           (r - b) >= 28 && (g - b) >= 12;
}

bool sea_ground(int r, int g, int b) {
    // 研究版木地板 + 稍暗木纹。
    if (r >= 185 && g >= 115 && b >= 65 && (r - g) >= 24 && (g - b) >= 18 &&
        (r - b) >= 50) {
        return true;
    }
    return r >= 150 && g >= 95 && b >= 50 && (r - g) >= 16 && (g - b) >= 12 &&
           (r - b) >= 36 && r > b;
}

float clamp01(float v) {
    return std::clamp(v, 0.0f, 1.0f);
}

}  // namespace

/**
 * 海盐客厅多点找色模板。
 *
 * 真相源：酱油 AutoJS 脚本（设计分辨率 1272×2772，颜色 0xAARRGGBB）。
 * - 偏移 = 设计像素 / 设计边长（与 等比x/y 一致）；
 * - 不移植「复活」UI 点击（算法包不得控手势/点按钮）；
 * - 船锚脚本为向下滑 → Avoidance::SLIDE；
 * - 大/小断崖拆分（双跳 / 单跳）；无专用绘制层。
 */
std::vector<MultiColorPattern> sea_salt_multicolor_rules(
    const SceneAlgorithmParamsNative& params) {
    std::vector<MultiColorPattern> rules;

    // 搜索区：脚本 region [290,1213,982,1229] → 左 0.228 / 顶 0.438 / 右 1.0 / 底 0.881。
    // 包参数 top/bottom 可覆盖竖直带；水平固定贴近脚本（避免扫 UI 左缘）。
    const float search_left = 0.228f;
    const float search_right = 1.0f;
    const float search_top =
        params.search_region_top_ratio > 0.05f ? params.search_region_top_ratio : 0.438f;
    const float search_bottom =
        params.search_region_bottom_ratio > search_top ? params.search_region_bottom_ratio
                                                       : 0.881f;
    const float thresh =
        params.multicolor_threshold > 0.0f ? params.multicolor_threshold : 10.0f;

    auto fill_search = [&](MultiColorPattern& p) {
        p.search_left_ratio = search_left;
        p.search_right_ratio = search_right;
        p.search_top_ratio = search_top;
        p.search_bottom_ratio = search_bottom;
        p.threshold = thresh;
    };

    // 大断崖 → SEA_PIT / DOUBLE_JUMP（脚本双击分支）
    // base -14134134 = 0xFF28548A
    {
        MultiColorPattern p{};
        p.base_r = 40;
        p.base_g = 84;
        p.base_b = 138;
        p.offsets = {
            {57.0f / 1272.0f, 86.0f / 2772.0f, 245u, 237u, 230u},    // -659994
            {206.0f / 1272.0f, 17.0f / 2772.0f, 254u, 250u, 242u},   // -66830
            {294.0f / 1272.0f, 65.0f / 2772.0f, 252u, 248u, 238u},   // -198418
            {633.0f / 1272.0f, 88.0f / 2772.0f, 254u, 248u, 241u},   // -67343
            {739.0f / 1272.0f, 168.0f / 2772.0f, 232u, 227u, 217u},  // -1514535
            {733.0f / 1272.0f, 29.0f / 2772.0f, 62u, 144u, 210u},    // -12676910
        };
        p.kind = Kind::SEA_PIT;
        p.avoidance = Avoidance::DOUBLE_JUMP;
        fill_search(p);
        rules.push_back(std::move(p));
    }

    // 小断崖 → SEA_PIT / JUMP
    // base -13605719 = 0xFF3064A9
    {
        MultiColorPattern p{};
        p.base_r = 48;
        p.base_g = 100;
        p.base_b = 169;
        p.offsets = {
            {69.0f / 1272.0f, 29.0f / 2772.0f, 248u, 240u, 231u},    // -462617
            {144.0f / 1272.0f, 63.0f / 2772.0f, 249u, 247u, 234u},   // -395286
            {333.0f / 1272.0f, 63.0f / 2772.0f, 252u, 248u, 238u},   // -198418
            {422.0f / 1272.0f, 87.0f / 2772.0f, 204u, 150u, 99u},    // -3369373
        };
        p.kind = Kind::SEA_PIT;
        p.avoidance = Avoidance::JUMP;
        fill_search(p);
        rules.push_back(std::move(p));
    }

    // 矮沙丘 → SAND_CASTLE / JUMP
    // base -400963 = 0xFFF9E1BD
    {
        MultiColorPattern p{};
        p.base_r = 249;
        p.base_g = 225;
        p.base_b = 189;
        p.offsets = {
            {70.0f / 1272.0f, -188.0f / 2772.0f, 249u, 230u, 198u},  // -399674
            {121.0f / 1272.0f, -210.0f / 2772.0f, 251u, 229u, 199u}, // -268857
            {198.0f / 1272.0f, -171.0f / 2772.0f, 229u, 190u, 135u}, // -1720697
            {225.0f / 1272.0f, 2.0f / 2772.0f, 193u, 156u, 127u},    // -4088705
        };
        p.kind = Kind::SAND_CASTLE;
        p.avoidance = Avoidance::JUMP;
        fill_search(p);
        rules.push_back(std::move(p));
    }

    // 高沙丘 → SAND_CASTLE / DOUBLE_JUMP
    // base -465469 = 0xFFF8E5C3
    {
        MultiColorPattern p{};
        p.base_r = 248;
        p.base_g = 229;
        p.base_b = 195;
        p.offsets = {
            {44.0f / 1272.0f, -216.0f / 2772.0f, 227u, 180u, 128u},  // -1854336
            {141.0f / 1272.0f, -413.0f / 2772.0f, 194u, 82u, 151u},  // -4042089
            {210.0f / 1272.0f, -180.0f / 2772.0f, 240u, 215u, 177u}, // -993359
            {231.0f / 1272.0f, -24.0f / 2772.0f, 189u, 138u, 105u},  // -4355479
        };
        p.kind = Kind::SAND_CASTLE;
        p.avoidance = Avoidance::DOUBLE_JUMP;
        fill_search(p);
        rules.push_back(std::move(p));
    }

    // 船锚 → HANGING_ANCHOR / SLIDE（脚本 swipe 向下 100 设计像素）
    // base -4603180 = 0xFFB9C2D4
    {
        MultiColorPattern p{};
        p.base_r = 185;
        p.base_g = 194;
        p.base_b = 212;
        p.offsets = {
            {68.0f / 1272.0f, 22.0f / 2772.0f, 108u, 128u, 159u},    // -9666401
            {150.0f / 1272.0f, -18.0f / 2772.0f, 221u, 225u, 235u},  // -2235925
            {50.0f / 1272.0f, 78.0f / 2772.0f, 120u, 124u, 134u},    // -8881018
            {116.0f / 1272.0f, 50.0f / 2772.0f, 82u, 80u, 81u},      // -11382703
            {11.0f / 1272.0f, -55.0f / 2772.0f, 171u, 122u, 63u},    // -5539265
        };
        p.kind = Kind::HANGING_ANCHOR;
        p.avoidance = Avoidance::SLIDE;
        fill_search(p);
        rules.push_back(std::move(p));
    }

    return rules;
}

/**
 * 在分析末尾追加声明式多点找色检测结果。
 *
 * 规则来自 sea_salt_multicolor_rules（编译期模板 + profile 阈值），
 * 不在帧路径解析 JSON。仅合并非 PLAYER 的障碍检测。
 */
void append_multicolor_detections(
    Result& out,
    const FrameView& frame,
    int enabled_kind_mask,
    const SceneAlgorithmParamsNative& params) {
    const auto rules = sea_salt_multicolor_rules(params);
    if (rules.empty()) return;
    const auto found = find_multi_color_patterns(
        frame,
        rules,
        enabled_kind_mask,
        params.multicolor_threshold);
    if (!found.error.empty()) return;
    for (const auto& det : found.detections) {
        if (det.kind == Kind::PLAYER) continue;
        out.detections.push_back(det);
    }
    if (!found.detections.empty()) {
        out.scene_confidence = std::max(out.scene_confidence, 0.72f);
    }
}

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

    const float ground_top =
        params.ground_search_top > 0.05f ? params.ground_search_top : 0.54f;
    const float ground_bottom =
        params.ground_search_bottom > ground_top ? params.ground_search_bottom : 0.84f;
    const int y0 = static_cast<int>(f.height * ground_top);
    const int y1 = static_cast<int>(f.height * ground_bottom);
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

    // 地面过弱时仍继续（用画面中下部先验），只降低置信度，避免整帧空跑。
    const float ground_min =
        params.ground_confidence_min > 0.0f ? params.ground_confidence_min : 0.26f;
    const bool weak_ground = best_score < ground_min * 0.35f;
    if (weak_ground) {
        best_y = static_cast<int>(f.height * 0.64f);
        best_score = std::max(best_score, 0.08f);
    }

    Component player_component{};
    bool player_found = false;
    if (detect_player) {
        // 海盐角色仍偏粉红；sweet_scene=true 更贴粉色核心。
        const auto players = player_candidates(f, work_width, best_y, true);
        player_found = choose_player(f, players, best_y, &player_component);
        if (player_found) {
            out.detections.push_back(
                {1, Kind::PLAYER, norm(player_component, f), .88f, false, false, Avoidance::NONE});
        }
    }

    // 玩家失败：固定参考框，继续扫障碍（批跑/FIXED_RATIO 一致）。
    if (!player_found) {
        const int fixed_right = static_cast<int>(f.width * std::clamp(fixed_player_x_ratio, 0.05f, 0.45f));
        const int divisor = std::max(8, params.fixed_player_width_divisor);
        const int fixed_left = std::max(0, fixed_right - std::max(8, f.width / divisor));
        const int top = static_cast<int>(f.height * params.fixed_player_top);
        const int bottom = static_cast<int>(f.height * params.fixed_player_bottom);
        Component fixed{fixed_left, top, fixed_right, bottom, 1};
        out.detections.push_back(
            {1, Kind::PLAYER, norm(fixed, f), detect_player ? .55f : 1.0f, false, false,
             Avoidance::NONE});
        player_component = fixed;
    }

    const int player_right = player_component.right;
    out.scene_confidence = clamp01(
        .35f * best_score + (player_found ? .55f : .25f) + (weak_ground ? 0.0f : .10f));

    const int x_stride = adaptive_stride(f, work_width);
    int hint = 300;

    // 海盐专用尺寸窗（相对视口），避免误用雕像/毛笔阈值过严。
    const float sand_w_min = 0.04f;
    const float sand_w_max = 0.42f;
    const float sand_h_min = 0.05f;
    const float sand_h_max = 0.45f;
    const float anchor_w_min = 0.03f;
    const float anchor_w_max = 0.30f;
    const float anchor_h_min = 0.10f;
    const float anchor_h_max = 0.60f;
    const float pit_w_min = 0.08f;
    const float pit_w_max = 0.85f;
    const float pit_h_min = 0.06f;
    const float pit_wide = 0.28f;

    // SOLID: 沙堡
    if (kind_enabled(enabled_kind_mask, Kind::SAND_CASTLE)) {
        auto parts = components(
            f,
            [&](int r, int g, int b, int x, int y) {
                if (x + 2 <= player_right) return false;
                return y > f.height * .30f && y < best_y + f.height * .08f && sea_sand(r, g, b);
            },
            x_stride,
            std::max(4, 12 / x_stride));
        auto castles = merge_components(
            std::move(parts),
            static_cast<int>(f.width * .04f),
            static_cast<int>(f.height * .06f));
        for (const auto& c : castles) {
            const float w = normalized_width(c, f);
            const float h = normalized_height(c, f);
            const float bottom_gap = std::abs(c.bottom - best_y) / static_cast<float>(f.height);
            if (w < sand_w_min || w > sand_w_max || h < sand_h_min || h > sand_h_max ||
                bottom_gap > .18f || c.right <= player_right) {
                continue;
            }
            const bool tall = h > 0.16f || w > 0.22f;
            out.detections.push_back(
                {hint++, Kind::SAND_CASTLE, norm(c, f), .84f, true, false,
                 tall ? Avoidance::DOUBLE_JUMP : Avoidance::JUMP});
        }
    }

    // HANGING: 船锚（每帧最多保留最靠近玩家的一个，抑制 UI 竖条误检）。
    if (kind_enabled(enabled_kind_mask, Kind::HANGING_ANCHOR)) {
        Component best_anchor{};
        float best_anchor_score = -1.f;
        auto anchors = column_regions(
            f,
            [](int r, int g, int b) {
                return sea_anchor_metal(r, g, b) || sea_anchor_rope(r, g, b);
            },
            static_cast<int>(f.height * .16f),
            best_y + static_cast<int>(f.height * .03f),
            x_stride,
            std::max(3, static_cast<int>(f.height * .045f) / x_stride),
            static_cast<int>(f.width * .032f),
            static_cast<int>(f.width * .028f));
        for (const auto& c : anchors) {
            const float w = normalized_width(c, f);
            const float h = normalized_height(c, f);
            const float aspect = h / std::max(w, .001f);
            if (w < anchor_w_min || w > anchor_w_max || h < anchor_h_min || h > anchor_h_max ||
                aspect < 1.35f || center_y(c, f) < .24f || c.right <= player_right) {
                continue;
            }
            const float metal = region_fraction(
                f, c, [](int r, int g, int b) { return sea_anchor_metal(r, g, b); });
            const float rope = region_fraction(
                f, c, [](int r, int g, int b) { return sea_anchor_rope(r, g, b); });
            if (metal < 0.04f || metal + rope < 0.12f) continue;
            // 优先：更靠近玩家 + 更高金属占比。
            const float dist = std::max(0.0f, (c.left - player_right) / static_cast<float>(f.width));
            const float score = metal * 1.2f + rope * 0.4f + h * 0.5f - dist * 0.8f;
            if (score > best_anchor_score) {
                best_anchor_score = score;
                best_anchor = c;
            }
        }
        if (best_anchor_score > 0.f) {
            Rect box = norm(best_anchor, f);
            box.left = std::max(0.0f, box.left - .02f);
            box.right = std::min(1.0f, box.right + .02f);
            box.top = std::max(0.0f, box.top - .015f);
            box.bottom = std::min(1.0f, box.bottom + .05f);
            out.detections.push_back(
                {hint++, Kind::HANGING_ANCHOR, box, .82f, true, false, Avoidance::SLIDE});
        }
    }

    // TERRAIN: 海坑
    // 真机截图里木地板色往往在缺口处仍连续，不能只靠「支撑断裂」。
    // 主策略：地面线下方「非木地板」列占比高的宽段 + 暗/水色佐证。
    if (kind_enabled(enabled_kind_mask, Kind::SEA_PIT) ||
        kind_enabled(enabled_kind_mask, Kind::PIT)) {
        const Kind pit_kind = kind_enabled(enabled_kind_mask, Kind::SEA_PIT)
            ? Kind::SEA_PIT
            : Kind::PIT;

        const int dy0 = std::min(f.height - 1, best_y + std::max(1, f.height / 120));
        const int dy1 = std::min(f.height, best_y + static_cast<int>(f.height * 0.30f));
        const int col_step = std::max(1, x_stride);
        const int row_step = std::max(1, x_stride);

        std::vector<float> void_col(static_cast<size_t>(f.width), 0.f);
        std::vector<float> water_col(static_cast<size_t>(f.width), 0.f);
        std::vector<float> dark_col(static_cast<size_t>(f.width), 0.f);
        for (int x = 0; x < f.width; x += col_step) {
            int total = 0;
            int non_wood = 0;
            int water = 0;
            int dark = 0;
            for (int y = dy0; y < dy1; y += row_step) {
                const auto p = f.pixels[static_cast<size_t>(y) * f.width + x];
                const int r = red(p);
                const int g = green(p);
                const int b = blue(p);
                ++total;
                const bool wood = sea_ground(r, g, b);
                if (!wood) ++non_wood;
                if (sea_water(r, g, b)) ++water;
                const int mx = std::max({r, g, b});
                if (!wood && mx < 140) ++dark;
            }
            const float inv = total > 0 ? 1.f / static_cast<float>(total) : 0.f;
            const float nv = non_wood * inv;
            const float wv = water * inv;
            const float dv = dark * inv;
            for (int xx = x; xx < std::min(f.width, x + col_step); ++xx) {
                void_col[static_cast<size_t>(xx)] = nv;
                water_col[static_cast<size_t>(xx)] = wv;
                dark_col[static_cast<size_t>(xx)] = dv;
            }
        }

        // 缺口列：非木占比高，或明显水色/暗空洞。
        std::vector<uint8_t> active(static_cast<size_t>(f.width), 0);
        for (int x = 0; x < f.width; ++x) {
            const float nv = void_col[static_cast<size_t>(x)];
            const float wv = water_col[static_cast<size_t>(x)];
            const float dv = dark_col[static_cast<size_t>(x)];
            active[static_cast<size_t>(x)] = static_cast<uint8_t>(
                (nv > 0.42f && (dv > 0.12f || wv > 0.05f)) || wv > 0.14f ||
                (nv > 0.55f && dv > 0.08f));
        }
        // 轻度平滑，避免碎列。
        {
            std::vector<uint8_t> tmp = active;
            const int rad = std::max(1, f.width / 90);
            for (int x = 0; x < f.width; ++x) {
                int hit = 0;
                int n = 0;
                for (int k = -rad; k <= rad; ++k) {
                    const int xx = x + k;
                    if (xx >= 0 && xx < f.width) {
                        ++n;
                        hit += active[static_cast<size_t>(xx)];
                    }
                }
                tmp[static_cast<size_t>(x)] =
                    static_cast<uint8_t>(hit * 2 >= n);  // >= 一半
            }
            active.swap(tmp);
        }

        const int start = std::max(player_right + 2, static_cast<int>(f.width * 0.14f));
        const int end = static_cast<int>(f.width * 0.99f);
        Component best_pit{};
        float best_pit_score = -1.f;
        for (int i = start; i < end;) {
            if (!active[static_cast<size_t>(i)]) {
                ++i;
                continue;
            }
            int j = i;
            while (j < end && active[static_cast<size_t>(j)]) ++j;
            const int width_px = j - i;
            if (width_px < static_cast<int>(f.width * 0.07f)) {
                i = std::max(j, i + 1);
                continue;
            }
            float void_mean = 0.f;
            float water_mean = 0.f;
            float dark_mean = 0.f;
            for (int x = i; x < j; ++x) {
                void_mean += void_col[static_cast<size_t>(x)];
                water_mean += water_col[static_cast<size_t>(x)];
                dark_mean += dark_col[static_cast<size_t>(x)];
            }
            const float inv_w = 1.f / static_cast<float>(std::max(1, width_px));
            void_mean *= inv_w;
            water_mean *= inv_w;
            dark_mean *= inv_w;
            if (void_mean < 0.40f && water_mean < 0.08f) {
                i = std::max(j, i + 1);
                continue;
            }
            // 排除整段都是沙堡暖色的区域。
            int sand_hits = 0;
            int sand_n = 0;
            for (int y = dy0; y < dy1; y += row_step * 2) {
                for (int x = i; x < j; x += col_step * 2) {
                    const auto p = f.pixels[static_cast<size_t>(y) * f.width + x];
                    ++sand_n;
                    if (sea_sand(red(p), green(p), blue(p))) ++sand_hits;
                }
            }
            const float sand_r = sand_n > 0 ? sand_hits / static_cast<float>(sand_n) : 0.f;
            if (sand_r > 0.40f && water_mean < 0.08f && dark_mean < 0.20f) {
                i = std::max(j, i + 1);
                continue;
            }

            Component cand{
                i,
                best_y,
                j,
                std::min(f.height, best_y + static_cast<int>(f.height * 0.26f)),
                width_px,
            };
            const float w = normalized_width(cand, f);
            const float h = normalized_height(cand, f);
            if (w < pit_w_min || w > pit_w_max || h < pit_h_min) {
                i = std::max(j, i + 1);
                continue;
            }
            const float dist =
                std::max(0.0f, (cand.left - player_right) / static_cast<float>(f.width));
            const float score =
                w * 1.1f + void_mean * 0.7f + water_mean * 1.2f + dark_mean * 0.6f -
                dist * 0.45f - sand_r * 0.5f;
            if (score > best_pit_score) {
                best_pit_score = score;
                best_pit = cand;
            }
            i = std::max(j, i + 1);
        }

        if (best_pit_score > 0.f) {
            const float w = normalized_width(best_pit, f);
            out.detections.push_back(
                {hint++, pit_kind, norm(best_pit, f), .83f, true, false,
                 w > pit_wide ? Avoidance::DOUBLE_JUMP : Avoidance::JUMP});
        }
    }

    if (out.scene_confidence < params.scene_confidence_floor &&
        static_cast<int>(out.detections.size()) > 1) {
        out.scene_confidence =
            std::max(out.scene_confidence, params.scene_confidence_floor * 0.85f);
    }

    // 追加声明式多点找色（模板 + profile 阈值；不解析 JSON）
    append_multicolor_detections(out, f, enabled_kind_mask, params);

    return out;
}

}  // namespace hzzs
