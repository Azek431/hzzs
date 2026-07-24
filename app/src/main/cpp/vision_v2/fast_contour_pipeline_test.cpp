#include "fast_contour_pipeline.h"

#include <array>
#include <cassert>
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
    const std::size_t offset = static_cast<std::size_t>((y * width + x) * 4);
    pixels[offset] = color.r;
    pixels[offset + 1] = color.g;
    pixels[offset + 2] = color.b;
    pixels[offset + 3] = 255;
}

StripClassProfile make_square_profile() {
    StripClassProfile profile{};
    profile.class_index = 1;
    profile.point_count = 3;
    profile.required_points = 3;
    profile.lut_point_index = 0;
    profile.expected_y = 20;
    profile.row_tolerance = 4;
    profile.full_w = 20;
    profile.full_h = 20;
    profile.points[0] = TemplatePoint{2, 2, Rgb{200, 20, 20}};
    profile.points[1] = TemplatePoint{16, 2, Rgb{20, 200, 20}};
    profile.points[2] = TemplatePoint{2, 16, Rgb{20, 20, 200}};
    profile.polygon_count = 4;
    profile.polygon[0] = PointF{0, 0};
    profile.polygon[1] = PointF{19, 0};
    profile.polygon[2] = PointF{19, 19};
    profile.polygon[3] = PointF{0, 19};
    return profile;
}

void paint_profile(
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

void test_detect_and_place() {
    constexpr int width = 64;
    constexpr int height = 64;
    std::vector<std::uint8_t> pixels;
    fill_rgba(pixels, width, height, Rgb{240, 240, 240});

    const StripClassProfile profile = make_square_profile();
    // seed at origin+(2,2); expected_y=20 with tol 4 → origin_y=18 keeps seed near band
    const int ox = 30;
    const int oy = 18;
    paint_profile(pixels, width, profile, ox, oy);

    const RgbaView image{pixels.data(), width, height, width * 4};
    const std::array profiles{profile};
    Rgb15Lut lut;
    assert(build_lut_from_profiles(lut, profiles, 8));

    StripScanParams params{};
    params.x_start = 0;
    params.stride = 1;
    params.anchor_threshold = 8;
    params.verify_threshold = 8;
    params.neighborhood_radius = 1;

    std::array<FixedStripHit, 4> hits{};
    const std::size_t count = detect_fixed_strips(image, lut, profiles, params, hits);
    assert(count == 1);
    assert(hits[0].found);
    assert(hits[0].class_index == 1);
    assert(hits[0].origin_x == ox);
    assert(hits[0].origin_y == oy);
    assert(hits[0].matched_points == 3);
    assert(hits[0].score > 0.5F);

    std::array<PointF, 8> polygon{};
    assert(place_profile_polygon(profile, hits[0].origin_x, hits[0].origin_y, polygon) == 4);
    const BoundsF bounds = bounds_from_points(std::span<const PointF>(polygon.data(), 4));
    assert(bounds.valid());
    assert(bounds.left == static_cast<float>(ox));
    assert(bounds.top == static_cast<float>(oy));
    assert(bounds.right == static_cast<float>(ox + 19));
    assert(bounds.bottom == static_cast<float>(oy + 19));

    std::array<PointF, 128> densified{};
    std::array<PointF, 128> refined{};
    const std::size_t refined_count = place_and_refine_contour(
        image,
        std::span<const PointF>(polygon.data(), 4),
        densified,
        refined,
        4.0F,
        2,
        1.0F,
        2.0F);
    assert(refined_count >= 4);
}

void test_wrong_band_rejected() {
    constexpr int width = 48;
    constexpr int height = 48;
    std::vector<std::uint8_t> pixels;
    fill_rgba(pixels, width, height, Rgb{240, 240, 240});
    StripClassProfile profile = make_square_profile();
    profile.expected_y = 40;
    profile.row_tolerance = 1;
    paint_profile(pixels, width, profile, 10, 5);  // seed y=7, far from 40

    const RgbaView image{pixels.data(), width, height, width * 4};
    const std::array profiles{profile};
    Rgb15Lut lut;
    assert(build_lut_from_profiles(lut, profiles, 8));
    StripScanParams params{};
    params.stride = 1;
    params.anchor_threshold = 8;
    params.verify_threshold = 8;
    std::array<FixedStripHit, 2> hits{};
    assert(detect_fixed_strips(image, lut, profiles, params, hits) == 0);
}

void test_out_of_bounds_template_rejected() {
    constexpr int width = 24;
    constexpr int height = 24;
    std::vector<std::uint8_t> pixels;
    fill_rgba(pixels, width, height, Rgb{240, 240, 240});
    StripClassProfile profile = make_square_profile();
    profile.expected_y = 10;
    // Place so origin would be negative if matched near edge with large offsets
    profile.points[0] = TemplatePoint{10, 10, Rgb{200, 20, 20}};
    profile.points[1] = TemplatePoint{11, 10, Rgb{20, 200, 20}};
    profile.points[2] = TemplatePoint{10, 11, Rgb{20, 20, 200}};
    profile.full_w = 20;
    profile.full_h = 20;
    set_rgb(pixels, width, 2, 10, profile.points[0].color);
    set_rgb(pixels, width, 3, 10, profile.points[1].color);
    set_rgb(pixels, width, 2, 11, profile.points[2].color);

    const RgbaView image{pixels.data(), width, height, width * 4};
    const std::array profiles{profile};
    Rgb15Lut lut;
    assert(build_lut_from_profiles(lut, profiles, 8));
    StripScanParams params{};
    params.stride = 1;
    params.anchor_threshold = 8;
    params.verify_threshold = 8;
    params.neighborhood_radius = 0;
    std::array<FixedStripHit, 2> hits{};
    // origin would be (2-10, 10-10)=(-8,0) → rejected
    assert(detect_fixed_strips(image, lut, profiles, params, hits) == 0);
}

void test_densify_capacity() {
    const std::array poly{
        PointF{0, 0}, PointF{10, 0}, PointF{10, 10}, PointF{0, 10},
    };
    std::array<PointF, 4> tiny{};
    assert(densify_closed_polygon(poly, tiny, 1.0F) == 0);

    std::array<PointF, 128> roomy{};
    const std::size_t count = densify_closed_polygon(poly, roomy, 5.0F);
    assert(count > poly.size());
}

void test_invalid_inputs() {
    Rgb15Lut lut;
    StripClassProfile bad = make_square_profile();
    bad.point_count = 0;
    const std::array profiles{bad};
    assert(!build_lut_from_profiles(lut, profiles, 8));

    std::array<PointF, 4> out{};
    assert(place_profile_polygon(make_square_profile(), 0, 0, std::span<PointF>(out.data(), 2)) == 0);
    assert(!bounds_from_points({}).valid());
    assert(!bounds_from_points(std::span<const PointF>{}).valid());
}

}  // namespace

int main() {
    test_detect_and_place();
    test_wrong_band_rejected();
    test_out_of_bounds_template_rejected();
    test_densify_capacity();
    test_invalid_inputs();
    return 0;
}
