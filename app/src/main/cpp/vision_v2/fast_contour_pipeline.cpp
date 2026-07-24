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
                if (origin_x < 0 || origin_y < 0 ||
                    origin_x + profile.full_w > image.width ||
                    origin_y + profile.full_h > image.height) {
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

}  // namespace hzzs::vision_v2
