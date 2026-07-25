#include "sea_salt_v3.h"

#include <cassert>
#include <cstdint>
#include <vector>

namespace {

using hzzs::vision_v3::ArgbFrameView;
using hzzs::vision_v3::SeaAlgorithmMode;
using hzzs::vision_v3::SeaSaltV3Engine;
using hzzs::vision_v3::SeaV3Config;

void test_empty_frame_agrees_and_does_not_act() {
    constexpr int width = 720;
    constexpr int height = 1612;
    std::vector<std::uint32_t> pixels(
        static_cast<std::size_t>(width) * height,
        0xff123456u);
    SeaV3Config config{};
    config.mode = SeaAlgorithmMode::SHADOW_COMPARE;
    SeaSaltV3Engine engine(config);
    const auto result = engine.detect(ArgbFrameView{pixels.data(), width, height, width});
    assert(result.agreement);
    assert(!result.primary.found);
    assert(!result.action_allowed);
}

void test_invalid_frame_fails_closed() {
    SeaSaltV3Engine engine;
    const auto result = engine.detect(ArgbFrameView{});
    assert(!result.primary.found);
    assert(!result.agreement);
    assert(!result.action_allowed);
}

void test_modes_are_explicit() {
    constexpr int width = 320;
    constexpr int height = 640;
    std::vector<std::uint32_t> pixels(
        static_cast<std::size_t>(width) * height,
        0xff000000u);
    const ArgbFrameView frame{pixels.data(), width, height, width};

    SeaV3Config exact_config{};
    exact_config.mode = SeaAlgorithmMode::SOY_SAUCE_EXACT;
    SeaSaltV3Engine exact_engine(exact_config);
    assert(exact_engine.detect(frame).agreement);

    SeaV3Config fast_config{};
    fast_config.mode = SeaAlgorithmMode::BUILTIN_FAST;
    SeaSaltV3Engine fast_engine(fast_config);
    assert(fast_engine.detect(frame).agreement);
}

}  // namespace

int main() {
    test_empty_frame_agrees_and_does_not_act();
    test_invalid_frame_fails_closed();
    test_modes_are_explicit();
    return 0;
}
