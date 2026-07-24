#pragma once

#include "fast_contour_core.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

namespace hzzs::vision_v2 {

// Fixed-capacity profile for strip-scan + sparse verification (sweet/bamboo-style).
// Coordinates are in the same pixel space as the supplied RgbaView (typically work image).
struct TemplatePoint {
    std::int16_t x{};
    std::int16_t y{};
    Rgb color{};
};

struct StripClassProfile {
    static constexpr std::size_t kMaxPoints = 8;
    static constexpr std::size_t kMaxPolygon = 24;

    std::uint8_t class_index{};
    std::uint8_t point_count{};
    std::uint8_t required_points{};
    std::uint8_t lut_point_index{};  // seeds LUT + strip scan
    std::int16_t expected_y{};
    std::int16_t row_tolerance{7};
    std::int16_t full_w{};
    std::int16_t full_h{};
    std::array<TemplatePoint, kMaxPoints> points{};
    std::uint8_t polygon_count{};
    std::array<PointF, kMaxPolygon> polygon{};
};

struct StripScanParams {
    int x_start{0};
    // If y1 <= y0, the scan band is derived from profiles' expected_y ± row_tolerance.
    int y0{0};
    int y1{0};
    int stride{1};
    std::uint8_t anchor_threshold{18};
    std::uint8_t verify_threshold{28};
    int neighborhood_radius{1};
};

struct FixedStripHit {
    bool found{};
    std::uint8_t profile_index{};
    std::uint8_t class_index{};
    int origin_x{};
    int origin_y{};
    std::uint8_t matched_points{};
    std::uint16_t color_cost{};
    float score{};
};

// Clears lut and ORs bit `profile_index` for each profile's lut_point color.
// Requires profiles.size() <= Rgb15Lut::kMaxClasses.
[[nodiscard]] bool build_lut_from_profiles(
    Rgb15Lut& lut,
    std::span<const StripClassProfile> profiles,
    std::uint8_t anchor_threshold) noexcept;

// At most one best hit per profile_index. Writes compact list into out_hits.
// Does not allocate; does not parse JSON.
[[nodiscard]] std::size_t detect_fixed_strips(
    const RgbaView& image,
    const Rgb15Lut& lut,
    std::span<const StripClassProfile> profiles,
    const StripScanParams& params,
    std::span<FixedStripHit> out_hits) noexcept;

// Translate profile polygon by origin into out (count = polygon_count). Returns 0 on failure.
[[nodiscard]] std::size_t place_profile_polygon(
    const StripClassProfile& profile,
    int origin_x,
    int origin_y,
    std::span<PointF> out) noexcept;

// Densify a closed polygon into out. Returns written count or 0 if capacity/input invalid.
[[nodiscard]] std::size_t densify_closed_polygon(
    std::span<const PointF> polygon,
    std::span<PointF> out,
    float spacing) noexcept;

// Densify (optional spacing<=0 skips densify and copies) then refine_contour_rgb.
// densified_scratch must hold densify output; refined_out holds final points.
// Returns refined point count or 0.
[[nodiscard]] std::size_t place_and_refine_contour(
    const RgbaView& image,
    std::span<const PointF> polygon,
    std::span<PointF> densified_scratch,
    std::span<PointF> refined_out,
    float spacing,
    int search_radius,
    float normal_gap,
    float distance_penalty) noexcept;

// Axis-aligned bounds from contour points: left, top, right, bottom (inclusive floats).
struct BoundsF {
    float left{};
    float top{};
    float right{};
    float bottom{};
    bool has_points{};

    [[nodiscard]] bool valid() const noexcept {
        return has_points && right >= left && bottom >= top;
    }
};

[[nodiscard]] BoundsF bounds_from_points(std::span<const PointF> points) noexcept;

// Scale a design-space profile into work-image pixels (sx = work_w / design_w).
// Points/expected_y/row_tolerance/full_* scale by sx/sy; polygon floats scale.
// Returns false if sx/sy invalid or profile geometry fails.
[[nodiscard]] bool scale_strip_profile(
    const StripClassProfile& design,
    float sx,
    float sy,
    StripClassProfile& out) noexcept;

// ---- Bamboo dynamic floor gap (column evidence, fixed capacity; no OpenCV) ----

struct BambooGapParams {
    float ground_y_ratio{0.609F};
    float left_ignore_ratio{0.20F};
    float min_width_ratio{0.075F};
    int min_width_px{18};
    int green_band_above{2};
    int green_band_below{62};
    int blur_half{8};           // box blur radius in columns
    float green_absent_max{7.0F};
    float dark_open_min{0.12F};
    int dark_band_start{12};
    int dark_band_end{85};
    float expand_evidence_max{12.0F};
    float bottom_extra_ratio{0.34F};
    std::uint8_t dark_r{100};
    std::uint8_t dark_g{100};
};

struct BambooGapHit {
    bool found{};
    int x0{};
    int x1{};
    int top{};
    int bottom{};
    float score{};
    float mean_evidence{};
};

// Writes up to out_hits.size() gaps. Uses fixed stack column buffers (max width 2048).
// No heap alloc; no morphology kernels beyond 1D run-length + box blur.
[[nodiscard]] std::size_t detect_bamboo_gaps(
    const RgbaView& image,
    const BambooGapParams& params,
    std::span<BambooGapHit> out_hits) noexcept;

// Axis-aligned rectangle polygon (4 verts) for a gap hit.
[[nodiscard]] std::size_t place_bamboo_gap_rect(
    const BambooGapHit& hit,
    std::span<PointF> out) noexcept;

}  // namespace hzzs::vision_v2
