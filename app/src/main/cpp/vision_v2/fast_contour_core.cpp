#include "fast_contour_core.h"

#include <algorithm>
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

[[nodiscard]] int rgb_l1(Rgb a, Rgb b) noexcept {
    return std::abs(static_cast<int>(a.r) - static_cast<int>(b.r)) +
           std::abs(static_cast<int>(a.g) - static_cast<int>(b.g)) +
           std::abs(static_cast<int>(a.b) - static_cast<int>(b.b));
}

[[nodiscard]] PointF normalized(PointF value) noexcept {
    const float length = std::sqrt(value.x * value.x + value.y * value.y);
    if (length <= 1.0e-6F) {
        return PointF{};
    }
    return PointF{value.x / length, value.y / length};
}

[[nodiscard]] Rgb sample_nearest(const RgbaView& image, PointF point) noexcept {
    return image.rgb_at_clamped(
        static_cast<int>(std::lround(point.x)),
        static_cast<int>(std::lround(point.y)));
}

}  // namespace

bool RgbaView::valid() const noexcept {
    return pixels != nullptr && width > 0 && height > 0 && row_stride_bytes >= width * 4;
}

Rgb RgbaView::rgb_at_clamped(int x, int y) const noexcept {
    if (!valid()) {
        return {};
    }
    x = std::clamp(x, 0, width - 1);
    y = std::clamp(y, 0, height - 1);
    const std::uint8_t* pixel = pixels + y * row_stride_bytes + x * 4;
    return Rgb{pixel[0], pixel[1], pixel[2]};
}

void Rgb15Lut::clear() noexcept {
    values_.fill(0);
}

bool Rgb15Lut::add_class_color(
    std::uint8_t class_index,
    Rgb color,
    std::uint8_t threshold) noexcept {
    if (class_index >= kMaxClasses) {
        return false;
    }
    const std::uint16_t bit = static_cast<std::uint16_t>(1u << class_index);
    for (std::uint16_t r5 = 0; r5 < 32; ++r5) {
        const int r = static_cast<int>(r5) * 8 + 4;
        if (std::abs(r - static_cast<int>(color.r)) > threshold + 4) {
            continue;
        }
        for (std::uint16_t g5 = 0; g5 < 32; ++g5) {
            const int g = static_cast<int>(g5) * 8 + 4;
            if (std::abs(g - static_cast<int>(color.g)) > threshold + 4) {
                continue;
            }
            for (std::uint16_t b5 = 0; b5 < 32; ++b5) {
                const int b = static_cast<int>(b5) * 8 + 4;
                if (std::abs(b - static_cast<int>(color.b)) <= threshold + 4) {
                    values_[(r5 << 10u) | (g5 << 5u) | b5] |= bit;
                }
            }
        }
    }
    return true;
}

std::uint16_t Rgb15Lut::lookup(Rgb color) const noexcept {
    return values_[code(color)];
}

std::uint16_t Rgb15Lut::code(Rgb color) noexcept {
    return static_cast<std::uint16_t>(
        ((static_cast<std::uint16_t>(color.r) >> 3u) << 10u) |
        ((static_cast<std::uint16_t>(color.g) >> 3u) << 5u) |
        (static_cast<std::uint16_t>(color.b) >> 3u));
}

MatchResult verify_sparse_pattern(
    const RgbaView& image,
    int anchor_x,
    int anchor_y,
    const SparsePattern& pattern,
    std::uint8_t threshold,
    int neighborhood_radius) noexcept {
    MatchResult result{};
    if (!image.valid() || pattern.points.empty() || pattern.required_points == 0 ||
        pattern.required_points > pattern.points.size() || neighborhood_radius < 0) {
        return result;
    }

    std::uint32_t cost = 0;
    std::uint8_t matched = 0;
    for (const PatternPoint& point : pattern.points) {
        const int target_x = anchor_x + point.dx;
        const int target_y = anchor_y + point.dy;
        int best = std::numeric_limits<int>::max();
        for (int dy = -neighborhood_radius; dy <= neighborhood_radius; ++dy) {
            for (int dx = -neighborhood_radius; dx <= neighborhood_radius; ++dx) {
                if (target_x + dx < 0 || target_x + dx >= image.width ||
                    target_y + dy < 0 || target_y + dy >= image.height) {
                    continue;
                }
                best = std::min(best, channel_distance(
                    image.rgb_at_clamped(target_x + dx, target_y + dy),
                    point.color));
            }
        }
        if (best <= threshold) {
            ++matched;
            cost += static_cast<std::uint32_t>(best);
        } else {
            cost += static_cast<std::uint32_t>(threshold) + 12u;
        }
    }

    result.matched_points = matched;
    result.color_cost = static_cast<std::uint16_t>(std::min<std::uint32_t>(cost, UINT16_MAX));
    result.matched = matched >= pattern.required_points;
    const float coverage = static_cast<float>(matched) / static_cast<float>(pattern.points.size());
    const float normalized_cost = static_cast<float>(cost) /
        std::max(1.0F, static_cast<float>(pattern.points.size()) * 120.0F);
    result.score = std::clamp(coverage * (1.0F - normalized_cost), 0.0F, 1.0F);
    return result;
}

std::size_t refine_contour_rgb(
    const RgbaView& image,
    std::span<const PointF> canonical,
    std::span<PointF> output,
    int search_radius,
    float normal_gap,
    float distance_penalty) noexcept {
    if (!image.valid() || canonical.size() < 3 || output.size() < canonical.size() ||
        search_radius < 0 || normal_gap < 0.0F || distance_penalty < 0.0F) {
        return 0;
    }

    for (std::size_t index = 0; index < canonical.size(); ++index) {
        const PointF previous = canonical[(index + canonical.size() - 1) % canonical.size()];
        const PointF current = canonical[index];
        const PointF following = canonical[(index + 1) % canonical.size()];
        const PointF tangent = normalized(PointF{following.x - previous.x, following.y - previous.y});
        const PointF normal{-tangent.y, tangent.x};

        float best_score = -std::numeric_limits<float>::infinity();
        float best_offset = 0.0F;
        for (int offset = -search_radius; offset <= search_radius; ++offset) {
            const PointF center{
                current.x + normal.x * static_cast<float>(offset),
                current.y + normal.y * static_cast<float>(offset)};
            const PointF inner{center.x - normal.x * normal_gap, center.y - normal.y * normal_gap};
            const PointF outer{center.x + normal.x * normal_gap, center.y + normal.y * normal_gap};
            const float score = static_cast<float>(rgb_l1(
                sample_nearest(image, inner),
                sample_nearest(image, outer))) - distance_penalty * std::abs(static_cast<float>(offset));
            if (score > best_score) {
                best_score = score;
                best_offset = static_cast<float>(offset);
            }
        }
        output[index] = PointF{
            current.x + normal.x * best_offset,
            current.y + normal.y * best_offset};
    }

    return canonical.size();
}

}  // namespace hzzs::vision_v2
