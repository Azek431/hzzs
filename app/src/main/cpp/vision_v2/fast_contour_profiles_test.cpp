#include "generated/fixed_profiles_v2.h"

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
    if (x < 0 || y < 0) {
        return;
    }
    const std::size_t offset = static_cast<std::size_t>((y * width + x) * 4);
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

void test_generated_table_shape() {
    assert(kFixedProfileCount == 5);
    assert(kFixedProfileNames[fixed_profile_index(FixedProfileId::SWEET_SPIKE)] != nullptr);
    const StripClassProfile& spike = kFixedProfilesV2[fixed_profile_index(FixedProfileId::SWEET_SPIKE)];
    assert(spike.class_index == 0);
    assert(spike.point_count == 7);
    assert(spike.required_points == 4);
    assert(spike.full_w == 125);
    assert(spike.full_h == 280);
    assert(spike.polygon_count >= 3);
    assert(spike.points[0].color.r == 181);
    assert(spike.points[0].color.g == 9);
    assert(spike.points[0].color.b == 48);
}

void test_detect_sweet_spike_synthetic() {
    const StripClassProfile& profile =
        kFixedProfilesV2[fixed_profile_index(FixedProfileId::SWEET_SPIKE)];
    // Canvas large enough for full template and expected scan band.
    const int width = 400;
    const int height = 600;
    std::vector<std::uint8_t> pixels;
    fill_rgba(pixels, width, height, Rgb{240, 240, 240});

    // Place origin so seed (lut point) lands near expected_y.
    const TemplatePoint& seed = profile.points[profile.lut_point_index];
    const int origin_x = 40;
    const int origin_y = static_cast<int>(profile.expected_y) - seed.y;
    assert(origin_y >= 0);
    assert(origin_x + profile.full_w <= width);
    assert(origin_y + profile.full_h <= height);
    paint_profile_points(pixels, width, profile, origin_x, origin_y);

    const RgbaView image{pixels.data(), width, height, width * 4};
    Rgb15Lut lut;
    assert(build_lut_from_profiles(lut, kFixedProfilesV2, 18));

    StripScanParams params{};
    params.x_start = 0;
    params.stride = 1;
    params.anchor_threshold = 18;
    params.verify_threshold = 28;
    params.neighborhood_radius = 1;

    std::array<FixedStripHit, 8> hits{};
    // Detect only the spike profile for a focused synthetic case.
    const std::array profiles{profile};
    Rgb15Lut single_lut;
    assert(build_lut_from_profiles(single_lut, profiles, 18));
    const std::size_t count = detect_fixed_strips(image, single_lut, profiles, params, hits);
    assert(count == 1);
    assert(hits[0].found);
    assert(hits[0].origin_x == origin_x);
    assert(hits[0].origin_y == origin_y);
    assert(hits[0].matched_points >= profile.required_points);

    std::array<PointF, StripClassProfile::kMaxPolygon> polygon{};
    const std::size_t poly_n =
        place_profile_polygon(profile, hits[0].origin_x, hits[0].origin_y, polygon);
    assert(poly_n == profile.polygon_count);
    const BoundsF bounds =
        bounds_from_points(std::span<const PointF>(polygon.data(), poly_n));
    assert(bounds.valid());
}

void test_all_profiles_build_lut() {
    Rgb15Lut lut;
    assert(build_lut_from_profiles(lut, kFixedProfilesV2, 18));
    // Spot-check first seed colors are in the LUT bitmask for their profile index.
    for (std::size_t i = 0; i < kFixedProfileCount; ++i) {
        const auto& profile = kFixedProfilesV2[i];
        const Rgb color = profile.points[profile.lut_point_index].color;
        const std::uint16_t bits = lut.lookup(color);
        assert((bits & static_cast<std::uint16_t>(1u << i)) != 0);
    }
}

}  // namespace

int main() {
    test_generated_table_shape();
    test_all_profiles_build_lut();
    test_detect_sweet_spike_synthetic();
    return 0;
}
