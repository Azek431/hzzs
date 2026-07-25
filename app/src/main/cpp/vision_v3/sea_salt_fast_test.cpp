#include "sea_salt_fast.h"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {

using hzzs::vision_v3::ArgbFrameView;
using hzzs::vision_v3::SeaFastConfig;
using hzzs::vision_v3::SeaSaltFastEngine;
using hzzs::vision_v3::SoyObstacleKind;
using hzzs::vision_v3::VerifyMetric;

constexpr int kBaseWidth = 1272;
constexpr int kBaseHeight = 2772;

[[nodiscard]] SeaFastConfig exact_compat_config() {
    SeaFastConfig config{};
    config.anchor_threshold = 10;
    config.verify_threshold = 10;
    config.neighborhood_radius = 0;
    config.enforce_source_anchor_region = true;
    config.minimum_scan_x_permille = 228;
    return config;
}

[[nodiscard]] std::uint32_t argb(int signed_value) {
    return static_cast<std::uint32_t>(signed_value);
}

[[nodiscard]] int js_round_scaled(int value, int dimension, int design_dimension) {
    return static_cast<int>(std::floor(
        static_cast<double>(value) * static_cast<double>(dimension) /
            static_cast<double>(design_dimension) +
        0.5));
}

void put(
    std::vector<std::uint32_t>& image,
    int width,
    int height,
    int x,
    int y,
    std::uint32_t color) {
    assert(x >= 0 && x < width && y >= 0 && y < height);
    image[static_cast<std::size_t>(y) * width + x] = color;
}


void paint_patch(
    std::vector<std::uint32_t>& image,
    int width,
    int height,
    int center_x,
    int center_y,
    int radius,
    std::uint32_t color) {
    for (int y = std::max(0, center_y - radius); y <= std::min(height - 1, center_y + radius); ++y) {
        for (int x = std::max(0, center_x - radius); x <= std::min(width - 1, center_x + radius); ++x) {
            put(image, width, height, x, y, color);
        }
    }
}

void put_scaled(
    std::vector<std::uint32_t>& image,
    int width,
    int height,
    int anchor_x,
    int anchor_y,
    int dx,
    int dy,
    std::uint32_t color) {
    put(
        image,
        width,
        height,
        anchor_x + js_round_scaled(dx, width, kBaseWidth),
        anchor_y + js_round_scaled(dy, height, kBaseHeight),
        color);
}

void draw_low_sandcastle(
    std::vector<std::uint32_t>& image,
    int width,
    int height,
    int anchor_x,
    int anchor_y) {
    put(image, width, height, anchor_x, anchor_y, argb(-400963));
    put_scaled(image, width, height, anchor_x, anchor_y, 70, -188, argb(-399674));
    put_scaled(image, width, height, anchor_x, anchor_y, 121, -210, argb(-268857));
    put_scaled(image, width, height, anchor_x, anchor_y, 198, -171, argb(-1720697));
    put_scaled(image, width, height, anchor_x, anchor_y, 225, 2, argb(-4088705));
    const int scan_x = anchor_x + js_round_scaled(225, width, kBaseWidth);
    const int scan_y = anchor_y + js_round_scaled(2, height, kBaseHeight);
    paint_patch(image, width, height, scan_x, scan_y, 5, argb(-4088705));
}

void draw_small_cliff(
    std::vector<std::uint32_t>& image,
    int width,
    int height,
    int anchor_x,
    int anchor_y) {
    put(image, width, height, anchor_x, anchor_y, argb(-13605719));
    put_scaled(image, width, height, anchor_x, anchor_y, 69, 29, argb(-462617));
    put_scaled(image, width, height, anchor_x, anchor_y, 144, 63, argb(-395286));
    put_scaled(image, width, height, anchor_x, anchor_y, 333, 63, argb(-198418));
    put_scaled(image, width, height, anchor_x, anchor_y, 422, 87, argb(-3369373));
    paint_patch(image, width, height, anchor_x, anchor_y, 5, argb(-13605719));
    put(image, width, height, anchor_x, anchor_y, argb(-13605719));
}

void test_low_sandcastle_exact_anchor() {
    const int width = 1272;
    const int height = 2772;
    std::vector<std::uint32_t> image(
        static_cast<std::size_t>(width) * height,
        0xff000000u);
    draw_low_sandcastle(image, width, height, 500, 1737);

    SeaSaltFastEngine engine(exact_compat_config());
    const auto hit = engine.detect(ArgbFrameView{image.data(), width, height, width});
    assert(hit.found);
    assert(hit.kind == SoyObstacleKind::LOW_SANDCASTLE);
    assert(hit.anchor_x == 500);
    assert(hit.anchor_y == 1737);
}

void test_scaled_720_width() {
    const int width = 720;
    const int height = 1612;
    std::vector<std::uint32_t> image(
        static_cast<std::size_t>(width) * height,
        0xff000000u);
    const int anchor_x = 360;
    const int anchor_y = 1009;
    draw_low_sandcastle(image, width, height, anchor_x, anchor_y);

    SeaSaltFastEngine engine(exact_compat_config());
    const auto hit = engine.detect(ArgbFrameView{image.data(), width, height, width});
    assert(hit.found);
    assert(hit.kind == SoyObstacleKind::LOW_SANDCASTLE);
    assert(hit.anchor_x == anchor_x);
    assert(hit.anchor_y == anchor_y);
}

void test_source_pattern_priority() {
    const int width = 1272;
    const int height = 2772;
    std::vector<std::uint32_t> image(
        static_cast<std::size_t>(width) * height,
        0xff000000u);
    draw_low_sandcastle(image, width, height, 350, 1737);
    draw_small_cliff(image, width, height, 800, 1758);

    SeaSaltFastEngine engine(exact_compat_config());
    const auto hit = engine.detect(ArgbFrameView{image.data(), width, height, width});
    assert(hit.found);
    assert(hit.kind == SoyObstacleKind::SMALL_CLIFF);
}

void test_anchor_outside_source_region_is_rejected() {
    const int width = 1272;
    const int height = 2772;
    std::vector<std::uint32_t> image(
        static_cast<std::size_t>(width) * height,
        0xff000000u);
    draw_low_sandcastle(image, width, height, 100, 1737);

    SeaSaltFastEngine engine(exact_compat_config());
    assert(!engine.detect(ArgbFrameView{image.data(), width, height, width}).found);
}

void test_mean_l1_metric() {
    const int width = 1272;
    const int height = 2772;
    std::vector<std::uint32_t> image(
        static_cast<std::size_t>(width) * height,
        0xff000000u);
    draw_low_sandcastle(image, width, height, 500, 1737);

    SeaFastConfig config = exact_compat_config();
    config.verification_metric = VerifyMetric::MEAN_L1;
    SeaSaltFastEngine engine(config);
    const auto hit = engine.detect(ArgbFrameView{image.data(), width, height, width});
    assert(hit.found);
    assert(hit.kind == SoyObstacleKind::LOW_SANDCASTLE);
}

void test_invalid_frame() {
    SeaSaltFastEngine engine(exact_compat_config());
    assert(!engine.detect({}).found);
}

}  // namespace

int main() {
    test_low_sandcastle_exact_anchor();
    test_scaled_720_width();
    test_source_pattern_priority();
    test_anchor_outside_source_region_is_rejected();
    test_mean_l1_metric();
    test_invalid_frame();
    return 0;
}
