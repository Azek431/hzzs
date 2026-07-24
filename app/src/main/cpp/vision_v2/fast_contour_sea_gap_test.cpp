#include "generated/sea_profiles_v2.h"

#include <array>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <vector>

using namespace hzzs::vision_v2;

namespace {

void fill_rgba(std::vector<std::uint8_t>& pixels, int width, int height, Rgb color) {
    pixels.assign(static_cast<std::size_t>(width * height * 4), 0);
    for (int i = 0; i < width * height; ++i) {
        pixels[static_cast<std::size_t>(i) * 4u + 0u] = color.r;
        pixels[static_cast<std::size_t>(i) * 4u + 1u] = color.g;
        pixels[static_cast<std::size_t>(i) * 4u + 2u] = color.b;
        pixels[static_cast<std::size_t>(i) * 4u + 3u] = 255;
    }
}

void set_rgb(std::vector<std::uint8_t>& pixels, int width, int x, int y, Rgb color) {
    if (x < 0 || y < 0) {
        return;
    }
    const std::size_t offset = static_cast<std::size_t>((y * width + x) * 4);
    if (offset + 3 >= pixels.size()) {
        return;
    }
    pixels[offset] = color.r;
    pixels[offset + 1] = color.g;
    pixels[offset + 2] = color.b;
    pixels[offset + 3] = 255;
}

void paint_profile_points(
    std::vector<std::uint8_t>& pixels,
    int width,
    const StripClassProfile& profile,
    int origin_x,
    int origin_y) {
    for (std::uint8_t i = 0; i < profile.point_count; ++i) {
        const TemplatePoint& point = profile.points[i];
        set_rgb(pixels, width, origin_x + point.x, origin_y + point.y, point.color);
    }
}

void test_sea_table_shape() {
    assert(kSeaProfileCount == 5);
    assert(kSeaBaseWidth == 1272);
    assert(kSeaBaseHeight == 2772);
    const StripClassProfile& cliff =
        kSeaProfilesV2[sea_profile_index(SeaProfileId::SEA_CLIFF_LARGE)];
    assert(cliff.point_count == 7);
    assert(cliff.lut_point_index == 0);
    assert(cliff.required_points == 4);
    assert(cliff.polygon_count >= 3);
    // Scan seed is first AutoJs color for 大断崖
    assert(cliff.points[0].x == 0);
    assert(cliff.points[0].y == 0);
}

void test_scale_and_detect_sea_cliff_small() {
    const StripClassProfile& design =
        kSeaProfilesV2[sea_profile_index(SeaProfileId::SEA_CLIFF_SMALL)];
    // Work image roughly half design width (common workWidth ~360 vs 1272).
    const float sx = 360.0F / static_cast<float>(kSeaBaseWidth);
    const float sy = sx;  // keep aspect for synthetic
    StripClassProfile profile{};
    assert(scale_strip_profile(design, sx, sy, profile));
    assert(profile.point_count == design.point_count);
    assert(std::abs(static_cast<int>(profile.expected_y) -
                    static_cast<int>(std::lround(design.expected_y * sy))) <= 1);

    const int width = 400;
    const int height = 900;
    std::vector<std::uint8_t> pixels;
    fill_rgba(pixels, width, height, Rgb{245, 245, 245});

    const TemplatePoint& seed = profile.points[profile.lut_point_index];
    const int origin_x = 40;
    const int origin_y = static_cast<int>(profile.expected_y) - seed.y;
    assert(origin_y >= 0);
    paint_profile_points(pixels, width, profile, origin_x, origin_y);

    // Ensure all samples on canvas.
    for (std::uint8_t i = 0; i < profile.point_count; ++i) {
        const int px = origin_x + profile.points[i].x;
        const int py = origin_y + profile.points[i].y;
        assert(px >= 0 && py >= 0 && px < width && py < height);
    }

    const RgbaView image{pixels.data(), width, height, width * 4};
    const std::array profiles{profile};
    Rgb15Lut lut;
    assert(build_lut_from_profiles(lut, profiles, 18));

    StripScanParams params{};
    params.x_start = 0;
    params.stride = 1;
    params.anchor_threshold = 18;
    params.verify_threshold = 28;
    params.neighborhood_radius = 1;

    std::array<FixedStripHit, 4> hits{};
    const std::size_t count = detect_fixed_strips(image, lut, profiles, params, hits);
    assert(count == 1);
    assert(hits[0].found);
    assert(hits[0].origin_x == origin_x);
    assert(hits[0].origin_y == origin_y);
    assert(hits[0].matched_points >= profile.required_points);

    std::array<PointF, StripClassProfile::kMaxPolygon> polygon{};
    const std::size_t poly_n =
        place_profile_polygon(profile, hits[0].origin_x, hits[0].origin_y, polygon);
    assert(poly_n == profile.polygon_count);
    assert(bounds_from_points(std::span<const PointF>(polygon.data(), poly_n)).valid());
}

void test_all_sea_profiles_build_lut() {
    Rgb15Lut lut;
    assert(build_lut_from_profiles(lut, kSeaProfilesV2, 16));
    for (std::size_t i = 0; i < kSeaProfileCount; ++i) {
        const auto& profile = kSeaProfilesV2[i];
        const Rgb color = profile.points[profile.lut_point_index].color;
        const std::uint16_t bits = lut.lookup(color);
        assert((bits & static_cast<std::uint16_t>(1u << i)) != 0);
    }
}

void test_bamboo_gap_synthetic() {
    constexpr int width = 200;
    constexpr int height = 200;
    std::vector<std::uint8_t> pixels;
    // Floor: bamboo green across most columns.
    fill_rgba(pixels, width, height, Rgb{40, 120, 50});

    const int ground = static_cast<int>(std::lround(0.609F * height));
    // Gap region: no green in band, dark below.
    const int gap_x0 = 80;
    const int gap_x1 = 130;
    for (int y = 0; y < height; ++y) {
        for (int x = gap_x0; x < gap_x1; ++x) {
            Rgb c{30, 30, 30};
            if (y < ground) {
                c = Rgb{180, 160, 140};  // not bamboo green
            }
            set_rgb(pixels, width, x, y, c);
        }
    }

    const RgbaView image{pixels.data(), width, height, width * 4};
    BambooGapParams params{};
    std::array<BambooGapHit, 4> hits{};
    const std::size_t count = detect_bamboo_gaps(image, params, hits);
    assert(count >= 1);
    assert(hits[0].found);
    assert(hits[0].x1 > hits[0].x0);
    // Gap should overlap synthetic hole.
    assert(hits[0].x0 < gap_x1 && hits[0].x1 > gap_x0);
    assert(hits[0].score >= 0.5F);

    std::array<PointF, 4> rect{};
    assert(place_bamboo_gap_rect(hits[0], rect) == 4);
    const BoundsF bounds = bounds_from_points(rect);
    assert(bounds.valid());
    assert(bounds.right > bounds.left);
}

void test_bamboo_gap_no_gap_on_uniform_green() {
    constexpr int width = 120;
    constexpr int height = 160;
    std::vector<std::uint8_t> pixels;
    fill_rgba(pixels, width, height, Rgb{40, 130, 55});
    const RgbaView image{pixels.data(), width, height, width * 4};
    BambooGapParams params{};
    std::array<BambooGapHit, 4> hits{};
    const std::size_t count = detect_bamboo_gaps(image, params, hits);
    assert(count == 0);
}

void test_bamboo_gap_contour_refine_shrinks_to_dark() {
    constexpr int width = 200;
    constexpr int height = 200;
    std::vector<std::uint8_t> pixels;
    // Bright floor everywhere, then a smaller dark hole inside a wider hit box.
    fill_rgba(pixels, width, height, Rgb{40, 120, 50});
    const int ground = static_cast<int>(std::lround(0.609F * height));
    // Wide gap columns so detect_bamboo_gaps fires.
    const int gap_x0 = 70;
    const int gap_x1 = 140;
    for (int y = 0; y < height; ++y) {
        for (int x = gap_x0; x < gap_x1; ++x) {
            Rgb c{30, 30, 30};
            if (y < ground) {
                c = Rgb{180, 160, 140};
            }
            set_rgb(pixels, width, x, y, c);
        }
    }
    // Bright inset band so dark core is narrower than full hit width.
    const int core_x0 = 90;
    const int core_x1 = 120;
    for (int y = ground; y < height; ++y) {
        for (int x = gap_x0; x < gap_x1; ++x) {
            if (x < core_x0 || x >= core_x1) {
                set_rgb(pixels, width, x, y, Rgb{200, 200, 200});
            }
        }
    }

    const RgbaView image{pixels.data(), width, height, width * 4};
    BambooGapParams params{};
    std::array<BambooGapHit, 4> hits{};
    const std::size_t count = detect_bamboo_gaps(image, params, hits);
    assert(count >= 1);

    std::array<PointF, 4> rect{};
    assert(place_bamboo_gap_rect(hits[0], rect) == 4);
    const BoundsF rect_bounds = bounds_from_points(rect);
    assert(rect_bounds.valid());

    std::array<PointF, 64> contour{};
    const std::size_t n = refine_bamboo_gap_contour(image, hits[0], contour, 5, 315);
    assert(n >= 4);
    const BoundsF refined = bounds_from_points(std::span<const PointF>(contour.data(), n));
    assert(refined.valid());
    // Refined dark core should be strictly narrower than the coarse rectangle.
    assert(refined.right - refined.left < rect_bounds.right - rect_bounds.left - 1.0F);
    // And should still sit near the synthetic dark core.
    assert(refined.left >= static_cast<float>(core_x0 - 8));
    assert(refined.right <= static_cast<float>(core_x1 + 8));
}

void test_bamboo_gap_contour_fallback_on_empty_dark() {
    BambooGapHit hit{};
    hit.found = true;
    hit.x0 = 10;
    hit.x1 = 40;
    hit.top = 50;
    hit.bottom = 100;
    hit.score = 0.8F;
    // Bright image: refine should fall back to rectangle.
    constexpr int width = 80;
    constexpr int height = 120;
    std::vector<std::uint8_t> pixels;
    fill_rgba(pixels, width, height, Rgb{220, 220, 220});
    const RgbaView image{pixels.data(), width, height, width * 4};
    std::array<PointF, 16> out{};
    const std::size_t n = refine_bamboo_gap_contour(image, hit, out, 2, 100);
    assert(n == 4);
    assert(out[0].x == 10.0F);
    assert(out[2].x == 40.0F);
}

}  // namespace

int main() {
    test_sea_table_shape();
    test_all_sea_profiles_build_lut();
    test_scale_and_detect_sea_cliff_small();
    test_bamboo_gap_synthetic();
    test_bamboo_gap_no_gap_on_uniform_green();
    test_bamboo_gap_contour_refine_shrinks_to_dark();
    test_bamboo_gap_contour_fallback_on_empty_dark();
    return 0;
}
