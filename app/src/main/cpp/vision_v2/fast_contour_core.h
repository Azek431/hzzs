#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

namespace hzzs::vision_v2 {

struct Rgb {
    std::uint8_t r{};
    std::uint8_t g{};
    std::uint8_t b{};
};

struct PointF {
    float x{};
    float y{};
};

struct RgbaView {
    const std::uint8_t* pixels{};
    int width{};
    int height{};
    int row_stride_bytes{};

    [[nodiscard]] bool valid() const noexcept;
    [[nodiscard]] Rgb rgb_at_clamped(int x, int y) const noexcept;
};

struct PatternPoint {
    std::int16_t dx{};
    std::int16_t dy{};
    Rgb color{};
};

struct SparsePattern {
    std::uint8_t class_index{};
    std::uint8_t required_points{};
    std::span<const PatternPoint> points{};
};

struct MatchResult {
    bool matched{};
    std::uint8_t matched_points{};
    std::uint16_t color_cost{};
    float score{};
};

class Rgb15Lut final {
public:
    static constexpr std::size_t kSize = 1u << 15u;
    static constexpr std::uint8_t kMaxClasses = 16;

    void clear() noexcept;
    bool add_class_color(std::uint8_t class_index, Rgb color, std::uint8_t threshold) noexcept;
    [[nodiscard]] std::uint16_t lookup(Rgb color) const noexcept;
    [[nodiscard]] static std::uint16_t code(Rgb color) noexcept;

private:
    std::array<std::uint16_t, kSize> values_{};
};

[[nodiscard]] MatchResult verify_sparse_pattern(
    const RgbaView& image,
    int anchor_x,
    int anchor_y,
    const SparsePattern& pattern,
    std::uint8_t threshold,
    int neighborhood_radius = 1) noexcept;

template <std::size_t Capacity>
class ContourArena final {
public:
    [[nodiscard]] bool append(std::span<const PointF> points, std::uint16_t& offset) noexcept {
        if (points.size() > Capacity - size_) {
            return false;
        }
        offset = static_cast<std::uint16_t>(size_);
        for (const PointF point : points) {
            points_[size_++] = point;
        }
        return true;
    }

    void clear() noexcept { size_ = 0; }
    [[nodiscard]] std::span<const PointF> points() const noexcept {
        return std::span<const PointF>(points_.data(), size_);
    }
    [[nodiscard]] std::size_t size() const noexcept { return size_; }

private:
    static_assert(Capacity <= UINT16_MAX, "contour offsets use uint16_t");
    std::array<PointF, Capacity> points_{};
    std::size_t size_{};
};

// Refines only the supplied sparse contour. No full-frame gradient image is built.
// Returns the number of points written to output, or 0 for invalid input/capacity.
[[nodiscard]] std::size_t refine_contour_rgb(
    const RgbaView& image,
    std::span<const PointF> canonical,
    std::span<PointF> output,
    int search_radius,
    float normal_gap,
    float distance_penalty) noexcept;

}  // namespace hzzs::vision_v2
