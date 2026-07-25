#include "soy_sauce_exact.h"

#include <array>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {

using hzzs::vision_v3::ArgbFrameView;
using hzzs::vision_v3::SoyActionType;
using hzzs::vision_v3::SoyObstacleKind;
using hzzs::vision_v3::SoySauceExactEngine;

constexpr int kW = 1272;
constexpr int kH = 2772;

std::uint32_t argb(int signed_value) {
    return static_cast<std::uint32_t>(signed_value);
}

void put(std::vector<std::uint32_t>& image, int x, int y, std::uint32_t c) {
    assert(x >= 0 && x < kW && y >= 0 && y < kH);
    image[static_cast<std::size_t>(y) * kW + x] = c;
}

void draw_low_sandcastle(std::vector<std::uint32_t>& image, int x, int y) {
    put(image, x, y, argb(-400963));
    put(image, x + 70, y - 188, argb(-399674));
    put(image, x + 121, y - 210, argb(-268857));
    put(image, x + 198, y - 171, argb(-1720697));
    put(image, x + 225, y + 2, argb(-4088705));
}

void draw_small_cliff(std::vector<std::uint32_t>& image, int x, int y) {
    put(image, x, y, argb(-13605719));
    put(image, x + 69, y + 29, argb(-462617));
    put(image, x + 144, y + 63, argb(-395286));
    put(image, x + 333, y + 63, argb(-198418));
    put(image, x + 422, y + 87, argb(-3369373));
}

void test_low_sandcastle_and_action() {
    std::vector<std::uint32_t> image(static_cast<std::size_t>(kW) * kH, 0xff000000u);
    draw_low_sandcastle(image, 500, 1800);
    SoySauceExactEngine engine;
    const auto hit = engine.detect(ArgbFrameView{image.data(), kW, kH, kW});
    assert(hit.found);
    assert(hit.kind == SoyObstacleKind::LOW_SANDCASTLE);
    assert(hit.action == SoyActionType::JUMP);
    assert(hit.action_triggered);
    assert(hit.anchor_x == 500 && hit.anchor_y == 1800);
    assert(hit.visual_bounds.left <= 500.0F / kW);
    assert(hit.visual_bounds.right > 725.0F / kW);
}

void test_pattern_priority_over_screen_position() {
    std::vector<std::uint32_t> image(static_cast<std::size_t>(kW) * kH, 0xff000000u);
    draw_low_sandcastle(image, 350, 1800);
    draw_small_cliff(image, 800, 1700);
    SoySauceExactEngine engine;
    const auto hit = engine.detect(ArgbFrameView{image.data(), kW, kH, kW});
    // Source object order checks small cliff before low sandcastle, regardless of x.
    assert(hit.found);
    assert(hit.kind == SoyObstacleKind::SMALL_CLIFF);
    assert(hit.anchor_x == 800);
}

void test_found_but_too_far_suppresses_action() {
    std::vector<std::uint32_t> image(static_cast<std::size_t>(kW) * kH, 0xff000000u);
    draw_low_sandcastle(image, 1000, 1800);
    SoySauceExactEngine engine;
    const auto hit = engine.detect(ArgbFrameView{image.data(), kW, kH, kW});
    assert(hit.found);
    assert(hit.kind == SoyObstacleKind::LOW_SANDCASTLE);
    assert(!hit.action_triggered);
    assert(hit.action == SoyActionType::NONE);
}

void test_invalid_frame() {
    SoySauceExactEngine engine;
    assert(!engine.detect({}).found);
}

}  // namespace

int main() {
    test_low_sandcastle_and_action();
    test_pattern_priority_over_screen_position();
    test_found_but_too_far_suppresses_action();
    test_invalid_frame();
    return 0;
}
