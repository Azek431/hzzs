#include "fast_contour_pipeline.h"

#include <algorithm>
#include <bit>
#include <cmath>
#include <limits>

namespace hzzs::vision_v2 {
namespace {

[[nodiscard]] int channel_distance(Rgb a, Rgb b) noexcept {
    const int dr = std::abs(static_cast<int>(a.r) - static_cast<int>(b.r));
    const int dg = std::abs(static_cast<int>(a.g) - static_cast<int>(b.g));
    const int db = std::abs(static_cast<int>(a.b) - static_cast<int>(b.b));
    return std::max({dr, dg, db});
}

[[nodiscard]] bool profile_geometry_ok(const StripClassProfile& profile) noexcept {
    return profile.point_count > 0 &&
        profile.point_count <= StripClassProfile::kMaxPoints &&
        profile.required_points > 0 &&
        profile.required_points <= profile.point_count &&
        profile.lut_point_index < profile.point_count &&
        profile.full_w > 0 &&
        profile.full_h > 0 &&
        profile.row_tolerance >= 0 &&
        profile.polygon_count <= StripClassProfile::kMaxPolygon;
}

[[nodiscard]] bool refine_anchor(
    const RgbaView& image,
    int seed_x,
    int seed_y,
    Rgb target,
    int radius,
    std::uint8_t threshold,
    int& out_x,
    int& out_y,
    int& out_diff) noexcept {
    int best = std::numeric_limits<int>::max();
    int best_x = seed_x;
    int best_y = seed_y;
    for (int dy = -radius; dy <= radius; ++dy) {
        for (int dx = -radius; dx <= radius; ++dx) {
            const int x = seed_x + dx;
            const int y = seed_y + dy;
            if (x < 0 || y < 0 || x >= image.width || y >= image.height) {
                continue;
            }
            const int diff = channel_distance(image.rgb_at_clamped(x, y), target);
            if (diff < best) {
                best = diff;
                best_x = x;
                best_y = y;
            }
        }
    }
    if (best > static_cast<int>(threshold)) {
        return false;
    }
    out_x = best_x;
    out_y = best_y;
    out_diff = best;
    return true;
}

[[nodiscard]] bool is_better_hit(const FixedStripHit& candidate, const FixedStripHit& current) noexcept {
    if (!current.found) {
        return true;
    }
    if (candidate.matched_points != current.matched_points) {
        return candidate.matched_points > current.matched_points;
    }
    if (candidate.color_cost != current.color_cost) {
        return candidate.color_cost < current.color_cost;
    }
    return candidate.score > current.score;
}

}  // namespace

bool build_lut_from_profiles(
    Rgb15Lut& lut,
    std::span<const StripClassProfile> profiles,
    std::uint8_t anchor_threshold) noexcept {
    if (profiles.size() > Rgb15Lut::kMaxClasses) {
        return false;
    }
    lut.clear();
    for (std::size_t index = 0; index < profiles.size(); ++index) {
        const StripClassProfile& profile = profiles[index];
        if (!profile_geometry_ok(profile)) {
            return false;
        }
        const TemplatePoint& seed = profile.points[profile.lut_point_index];
        if (!lut.add_class_color(static_cast<std::uint8_t>(index), seed.color, anchor_threshold)) {
            return false;
        }
    }
    return true;
}

std::size_t detect_fixed_strips(
    const RgbaView& image,
    const Rgb15Lut& lut,
    std::span<const StripClassProfile> profiles,
    const StripScanParams& params,
    std::span<FixedStripHit> out_hits) noexcept {
    if (!image.valid() || profiles.empty() || profiles.size() > Rgb15Lut::kMaxClasses ||
        params.stride < 1 || params.neighborhood_radius < 0 || params.x_start < 0) {
        return 0;
    }
    for (const StripClassProfile& profile : profiles) {
        if (!profile_geometry_ok(profile)) {
            return 0;
        }
    }

    int y0 = params.y0;
    int y1 = params.y1;
    if (y1 <= y0) {
        y0 = std::numeric_limits<int>::max();
        y1 = 0;
        for (const StripClassProfile& profile : profiles) {
            y0 = std::min(y0, static_cast<int>(profile.expected_y) - profile.row_tolerance);
            y1 = std::max(y1, static_cast<int>(profile.expected_y) + profile.row_tolerance + 1);
        }
    }
    y0 = std::clamp(y0, 0, image.height);
    y1 = std::clamp(y1, 0, image.height);
    if (y1 <= y0 || params.x_start >= image.width) {
        return 0;
    }

    std::array<FixedStripHit, Rgb15Lut::kMaxClasses> best{};
    std::array<PatternPoint, StripClassProfile::kMaxPoints> pattern_points{};

    for (int y = y0; y < y1; y += params.stride) {
        for (int x = params.x_start; x < image.width; x += params.stride) {
            std::uint16_t bits = lut.lookup(image.rgb_at_clamped(x, y));
            while (bits != 0) {
                const unsigned profile_index = static_cast<unsigned>(std::countr_zero(bits));
                bits = static_cast<std::uint16_t>(bits & static_cast<std::uint16_t>(bits - 1u));
                if (profile_index >= profiles.size()) {
                    continue;
                }

                const StripClassProfile& profile = profiles[static_cast<std::size_t>(profile_index)];
                if (std::abs(y - static_cast<int>(profile.expected_y)) > profile.row_tolerance) {
                    continue;
                }

                const TemplatePoint& seed = profile.points[profile.lut_point_index];
                int ax = 0;
                int ay = 0;
                int adiff = 0;
                if (!refine_anchor(
                        image,
                        x,
                        y,
                        seed.color,
                        params.neighborhood_radius,
                        params.anchor_threshold,
                        ax,
                        ay,
                        adiff)) {
                    continue;
                }

                const int origin_x = ax - seed.x;
                const int origin_y = ay - seed.y;
                // Points may be negative relative to origin (sea-salt scan anchors).
                // Require every template sample to land inside the image.
                bool origin_ok = true;
                for (std::uint8_t i = 0; i < profile.point_count; ++i) {
                    const int px = origin_x + profile.points[i].x;
                    const int py = origin_y + profile.points[i].y;
                    if (px < 0 || py < 0 || px >= image.width || py >= image.height) {
                        origin_ok = false;
                        break;
                    }
                }
                if (!origin_ok) {
                    continue;
                }

                for (std::uint8_t i = 0; i < profile.point_count; ++i) {
                    const TemplatePoint& point = profile.points[i];
                    pattern_points[i] = PatternPoint{
                        static_cast<std::int16_t>(point.x),
                        static_cast<std::int16_t>(point.y),
                        point.color};
                }
                const SparsePattern pattern{
                    profile.class_index,
                    profile.required_points,
                    std::span<const PatternPoint>(pattern_points.data(), profile.point_count)};
                const MatchResult match = verify_sparse_pattern(
                    image,
                    origin_x,
                    origin_y,
                    pattern,
                    params.verify_threshold,
                    params.neighborhood_radius);
                if (!match.matched) {
                    continue;
                }

                FixedStripHit candidate{};
                candidate.found = true;
                candidate.profile_index = static_cast<std::uint8_t>(profile_index);
                candidate.class_index = profile.class_index;
                candidate.origin_x = origin_x;
                candidate.origin_y = origin_y;
                candidate.matched_points = match.matched_points;
                candidate.color_cost = match.color_cost;
                candidate.score = match.score;
                if (is_better_hit(candidate, best[static_cast<std::size_t>(profile_index)])) {
                    best[static_cast<std::size_t>(profile_index)] = candidate;
                }
            }
        }
    }

    std::size_t written = 0;
    for (std::size_t index = 0; index < profiles.size() && written < out_hits.size(); ++index) {
        if (best[index].found) {
            out_hits[written++] = best[index];
        }
    }
    return written;
}

std::size_t place_profile_polygon(
    const StripClassProfile& profile,
    int origin_x,
    int origin_y,
    std::span<PointF> out) noexcept {
    if (!profile_geometry_ok(profile) || profile.polygon_count < 3 ||
        out.size() < profile.polygon_count) {
        return 0;
    }
    for (std::uint8_t i = 0; i < profile.polygon_count; ++i) {
        out[i] = PointF{
            profile.polygon[i].x + static_cast<float>(origin_x),
            profile.polygon[i].y + static_cast<float>(origin_y)};
    }
    return profile.polygon_count;
}

std::size_t densify_closed_polygon(
    std::span<const PointF> polygon,
    std::span<PointF> out,
    float spacing) noexcept {
    if (polygon.size() < 2 || out.empty() || spacing <= 0.0F) {
        return 0;
    }
    std::size_t written = 0;
    for (std::size_t i = 0; i < polygon.size(); ++i) {
        const PointF a = polygon[i];
        const PointF b = polygon[(i + 1) % polygon.size()];
        const float dx = b.x - a.x;
        const float dy = b.y - a.y;
        const float length = std::sqrt(dx * dx + dy * dy);
        const int count = std::max(1, static_cast<int>(std::ceil(length / spacing)));
        for (int j = 0; j < count; ++j) {
            if (written >= out.size()) {
                return 0;
            }
            const float t = static_cast<float>(j) / static_cast<float>(count);
            out[written++] = PointF{a.x + dx * t, a.y + dy * t};
        }
    }
    return written;
}

std::size_t place_and_refine_contour(
    const RgbaView& image,
    std::span<const PointF> polygon,
    std::span<PointF> densified_scratch,
    std::span<PointF> refined_out,
    float spacing,
    int search_radius,
    float normal_gap,
    float distance_penalty) noexcept {
    if (!image.valid() || polygon.size() < 3 || refined_out.empty()) {
        return 0;
    }

    std::span<const PointF> source = polygon;
    std::size_t densified_count = 0;
    if (spacing > 0.0F) {
        densified_count = densify_closed_polygon(polygon, densified_scratch, spacing);
        if (densified_count < 3) {
            return 0;
        }
        source = densified_scratch.first(densified_count);
    }
    if (refined_out.size() < source.size()) {
        return 0;
    }
    return refine_contour_rgb(
        image,
        source,
        refined_out.first(source.size()),
        search_radius,
        normal_gap,
        distance_penalty);
}

BoundsF bounds_from_points(std::span<const PointF> points) noexcept {
    if (points.empty()) {
        return {};
    }
    BoundsF bounds{
        points[0].x,
        points[0].y,
        points[0].x,
        points[0].y,
        true};
    for (const PointF point : points) {
        bounds.left = std::min(bounds.left, point.x);
        bounds.top = std::min(bounds.top, point.y);
        bounds.right = std::max(bounds.right, point.x);
        bounds.bottom = std::max(bounds.bottom, point.y);
    }
    return bounds;
}

bool scale_strip_profile(
    const StripClassProfile& design,
    float sx,
    float sy,
    StripClassProfile& out) noexcept {
    if (!profile_geometry_ok(design) || !(sx > 0.0F) || !(sy > 0.0F)) {
        return false;
    }
    out = design;
    out.expected_y = static_cast<std::int16_t>(std::lround(static_cast<float>(design.expected_y) * sy));
    out.row_tolerance = static_cast<std::int16_t>(std::max(
        1,
        static_cast<int>(std::lround(static_cast<float>(design.row_tolerance) * sy))));
    out.full_w = static_cast<std::int16_t>(std::max(
        1,
        static_cast<int>(std::lround(static_cast<float>(design.full_w) * sx))));
    out.full_h = static_cast<std::int16_t>(std::max(
        1,
        static_cast<int>(std::lround(static_cast<float>(design.full_h) * sy))));
    for (std::uint8_t i = 0; i < design.point_count; ++i) {
        out.points[i].x = static_cast<std::int16_t>(
            std::lround(static_cast<float>(design.points[i].x) * sx));
        out.points[i].y = static_cast<std::int16_t>(
            std::lround(static_cast<float>(design.points[i].y) * sy));
        out.points[i].color = design.points[i].color;
    }
    for (std::uint8_t i = 0; i < design.polygon_count; ++i) {
        out.polygon[i].x = design.polygon[i].x * sx;
        out.polygon[i].y = design.polygon[i].y * sy;
    }
    return profile_geometry_ok(out);
}

namespace {

constexpr int kMaxGapWidth = 2048;

[[nodiscard]] bool is_bamboo_green(Rgb c) noexcept {
    // Match tools/vision_v2/bamboo_gap_detector.py:
    // (g > b + 25) & (g >= r - 18) & (g > 65) & (r < 205)
    const int r = static_cast<int>(c.r);
    const int g = static_cast<int>(c.g);
    const int b = static_cast<int>(c.b);
    return g > b + 25 && g >= r - 18 && g > 65 && r < 205;
}

void box_blur_1d(const float* in, float* out, int n, int half) noexcept {
    if (n <= 0) {
        return;
    }
    if (half <= 0) {
        for (int i = 0; i < n; ++i) {
            out[i] = in[i];
        }
        return;
    }
    // Inclusive window [i-half, i+half], clamped; prefix sums for O(n).
    std::array<float, kMaxGapWidth + 1> prefix{};
    prefix[0] = 0.0F;
    for (int i = 0; i < n; ++i) {
        prefix[static_cast<std::size_t>(i + 1)] = prefix[static_cast<std::size_t>(i)] + in[i];
    }
    for (int i = 0; i < n; ++i) {
        const int lo = std::max(0, i - half);
        const int hi = std::min(n - 1, i + half);
        const float sum =
            prefix[static_cast<std::size_t>(hi + 1)] - prefix[static_cast<std::size_t>(lo)];
        out[i] = sum / static_cast<float>(hi - lo + 1);
    }
}

}  // namespace

std::size_t detect_bamboo_gaps(
    const RgbaView& image,
    const BambooGapParams& params,
    std::span<BambooGapHit> out_hits) noexcept {
    if (!image.valid() || out_hits.empty() || image.width > kMaxGapWidth || image.width < 8 ||
        image.height < 32 || !(params.ground_y_ratio > 0.0F) || !(params.ground_y_ratio < 1.0F)) {
        return 0;
    }

    const int w = image.width;
    const int h = image.height;
    const int ground = static_cast<int>(std::lround(params.ground_y_ratio * static_cast<float>(h)));
    const int y0 = std::clamp(ground - params.green_band_above, 0, h);
    const int y1 = std::clamp(ground + params.green_band_below, 0, h);
    if (y1 <= y0) {
        return 0;
    }

    std::array<float, kMaxGapWidth> evidence_raw{};
    std::array<float, kMaxGapWidth> evidence{};
    std::array<float, kMaxGapWidth> dark_ratio{};
    std::array<std::uint8_t, kMaxGapWidth> absent{};

    for (int x = 0; x < w; ++x) {
        float green_sum = 0.0F;
        for (int y = y0; y < y1; ++y) {
            if (is_bamboo_green(image.rgb_at_clamped(x, y))) {
                green_sum += 1.0F;
            }
        }
        evidence_raw[static_cast<std::size_t>(x)] = green_sum;

        const int dark0 = std::clamp(ground + params.dark_band_start, 0, h);
        const int dark1 = std::clamp(ground + params.dark_band_end, 0, h);
        float dark = 0.0F;
        int dark_n = 0;
        for (int y = dark0; y < dark1; ++y) {
            const Rgb c = image.rgb_at_clamped(x, y);
            if (c.r < params.dark_r && c.g < params.dark_g) {
                dark += 1.0F;
            }
            ++dark_n;
        }
        dark_ratio[static_cast<std::size_t>(x)] =
            dark_n > 0 ? dark / static_cast<float>(dark_n) : 0.0F;
    }

    box_blur_1d(evidence_raw.data(), evidence.data(), w, params.blur_half);

    const int left_ignore = static_cast<int>(
        std::lround(params.left_ignore_ratio * static_cast<float>(w)));
    for (int x = 0; x < w; ++x) {
        bool is_absent = evidence[static_cast<std::size_t>(x)] < params.green_absent_max &&
            dark_ratio[static_cast<std::size_t>(x)] > params.dark_open_min;
        if (x < left_ignore) {
            is_absent = false;
        }
        absent[static_cast<std::size_t>(x)] = is_absent ? 1u : 0u;
    }

    // Simple 1D close/open: dilate then erode with half-widths approximating Python kernels.
    std::array<std::uint8_t, kMaxGapWidth> tmp{};
    const int close_half = 5;
    const int open_half = 4;
    for (int x = 0; x < w; ++x) {
        bool any = false;
        for (int dx = -close_half; dx <= close_half; ++dx) {
            const int xx = x + dx;
            if (xx >= 0 && xx < w && absent[static_cast<std::size_t>(xx)]) {
                any = true;
                break;
            }
        }
        tmp[static_cast<std::size_t>(x)] = any ? 1u : 0u;
    }
    for (int x = 0; x < w; ++x) {
        bool all = true;
        for (int dx = -open_half; dx <= open_half; ++dx) {
            const int xx = x + dx;
            if (xx < 0 || xx >= w || !tmp[static_cast<std::size_t>(xx)]) {
                all = false;
                break;
            }
        }
        absent[static_cast<std::size_t>(x)] = all ? 1u : 0u;
    }

    const int min_width = std::max(
        params.min_width_px,
        static_cast<int>(std::lround(params.min_width_ratio * static_cast<float>(w))));

    std::size_t written = 0;
    int run_start = -1;
    for (int x = 0; x <= w; ++x) {
        const bool on = x < w && absent[static_cast<std::size_t>(x)];
        if (on && run_start < 0) {
            run_start = x;
        } else if (!on && run_start >= 0) {
            int x0 = run_start;
            int x1 = x;
            // Expand while evidence stays low (Python: expand while evidence < 12).
            while (x0 > 0 &&
                   evidence[static_cast<std::size_t>(x0)] < params.expand_evidence_max) {
                --x0;
            }
            while (x1 < w - 1 &&
                   evidence[static_cast<std::size_t>(x1)] < params.expand_evidence_max) {
                ++x1;
            }
            x0 = std::max(0, x0 + 1);
            x1 = std::min(w, x1);
            run_start = -1;
            if (x1 - x0 < min_width || written >= out_hits.size()) {
                continue;
            }

            // Top edge: per-column max |dL| near ground (luma proxy = (r+g+b)/3).
            float top_acc = 0.0F;
            int top_n = 0;
            const int col_y0 = std::clamp(ground - 12, 0, h - 2);
            const int col_y1 = std::clamp(ground + 18, 1, h);
            for (int cx = x0; cx < x1; cx += 3) {
                int best_y = ground;
                int best_diff = -1;
                for (int y = col_y0; y + 1 < col_y1; ++y) {
                    const Rgb a = image.rgb_at_clamped(cx, y);
                    const Rgb b = image.rgb_at_clamped(cx, y + 1);
                    const int la = static_cast<int>(a.r) + a.g + a.b;
                    const int lb = static_cast<int>(b.r) + b.g + b.b;
                    const int diff = std::abs(la - lb);
                    if (diff > best_diff) {
                        best_diff = diff;
                        best_y = y;
                    }
                }
                top_acc += static_cast<float>(best_y);
                ++top_n;
            }
            const int top = top_n > 0 ? static_cast<int>(std::lround(top_acc / static_cast<float>(top_n)))
                                     : ground;
            const int bottom = std::min(
                h - 1,
                ground + static_cast<int>(std::lround(params.bottom_extra_ratio * static_cast<float>(h))));

            float mean_e = 0.0F;
            for (int cx = x0; cx < x1; ++cx) {
                mean_e += evidence[static_cast<std::size_t>(cx)];
            }
            mean_e /= static_cast<float>(std::max(1, x1 - x0));
            const float score = std::clamp(1.0F - mean_e / 12.0F, 0.5F, 1.0F);

            BambooGapHit hit{};
            hit.found = true;
            hit.x0 = x0;
            hit.x1 = x1;
            hit.top = top;
            hit.bottom = bottom;
            hit.score = score;
            hit.mean_evidence = mean_e;
            out_hits[written++] = hit;
        }
    }
    return written;
}

std::size_t place_bamboo_gap_rect(
    const BambooGapHit& hit,
    std::span<PointF> out) noexcept {
    if (!hit.found || out.size() < 4 || hit.x1 <= hit.x0 || hit.bottom < hit.top) {
        return 0;
    }
    const float x0 = static_cast<float>(hit.x0);
    const float x1 = static_cast<float>(hit.x1);
    const float y0 = static_cast<float>(hit.top);
    const float y1 = static_cast<float>(hit.bottom);
    out[0] = PointF{x0, y0};
    out[1] = PointF{x1, y0};
    out[2] = PointF{x1, y1};
    out[3] = PointF{x0, y1};
    return 4;
}

}  // namespace hzzs::vision_v2
