#include "../../main/cpp/algorithm_runtime.h"
#include "../../main/cpp/color_components.h"
#include "../../main/cpp/vision_engine.h"

#include <atomic>
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <iostream>
#include <limits>
#include <thread>
#include <vector>

namespace {

hzzs::AlgorithmRuntimeProfileNative valid_network_profile() {
    auto profile = hzzs::make_builtin_profile(1);
    std::snprintf(profile.algorithm_id, sizeof(profile.algorithm_id), "net.sample.v1");
    std::snprintf(profile.version, sizeof(profile.version), "1.0.1");
    profile.is_builtin = 0;
    profile.scenes[1].gap_width_min = 0.12f;
    profile.scenes[1].player_confidence_floor = 0.40f;
    return profile;
}

void put_pixel(
    std::vector<uint32_t>& pixels,
    int width,
    int height,
    int x,
    int y,
    int32_t argb) {
    assert(x >= 0 && x < width && y >= 0 && y < height);
    pixels[static_cast<size_t>(y) * width + x] = static_cast<uint32_t>(argb);
}

void draw_low_sandcastle(
    std::vector<uint32_t>& pixels,
    int width,
    int height,
    int x,
    int y) {
    put_pixel(pixels, width, height, x, y, -400963);
    put_pixel(pixels, width, height, x + 70, y - 188, -399674);
    put_pixel(pixels, width, height, x + 121, y - 210, -268857);
    put_pixel(pixels, width, height, x + 198, y - 171, -1720697);
    put_pixel(pixels, width, height, x + 225, y + 2, -4088705);
}

void test_native_sparse_respects_kind_mask_and_source() {
    using namespace hzzs;
    constexpr int width = 1272;
    constexpr int height = 2772;
    std::vector<uint32_t> pixels(static_cast<size_t>(width) * height, 0xff000000u);
    draw_low_sandcastle(pixels, width, height, 500, 1800);
    const FrameView frame{pixels.data(), width, height};
    const auto params = make_native_vision_profile(1).scenes[2];
    const int sandcastle_mask = 1 << static_cast<int>(Kind::SAND_CASTLE);

    const auto enabled = analyze_sea_salt_sparse(
        frame, 320, sandcastle_mask, false, 0.185f, params, nullptr);
    const auto obstacle = std::find_if(
        enabled.detections.begin(),
        enabled.detections.end(),
        [](const Detection& detection) { return detection.kind != Kind::PLAYER; });
    assert(obstacle != enabled.detections.end());
    assert(obstacle->kind == Kind::SAND_CASTLE);
    assert(obstacle->source == DetectionSource::NATIVE_SPARSE);
    assert(obstacle->track_hint == 10);

    const auto disabled = analyze_sea_salt_sparse(
        frame, 320, 0, false, 0.185f, params, nullptr);
    assert(std::none_of(
        disabled.detections.begin(),
        disabled.detections.end(),
        [](const Detection& detection) { return detection.kind != Kind::PLAYER; }));
}

void test_detection_source_contract() {
    using namespace hzzs;
    static_assert(static_cast<int32_t>(DetectionSource::DEFAULT_HEURISTIC) == 0);
    static_assert(static_cast<int32_t>(DetectionSource::MULTICOLOR) == 1);
    static_assert(static_cast<int32_t>(DetectionSource::SOY_EXACT) == 2);
    static_assert(static_cast<int32_t>(DetectionSource::SEA_FAST) == 3);
    static_assert(static_cast<int32_t>(DetectionSource::NATIVE_SPARSE) == 4);

    const Detection detection{
        7,
        DetectionSource::MULTICOLOR,
        Kind::SAND_CASTLE,
        Rect{0.1f, 0.2f, 0.3f, 0.4f},
        0.8f,
        true,
        false,
        Avoidance::JUMP,
    };
    assert(detection.track_hint == 7);
    assert(detection.source == DetectionSource::MULTICOLOR);
    assert(detection.kind == Kind::SAND_CASTLE);
    assert(detection.bounds.left == 0.1f);
    assert(detection.confidence == 0.8f);
    assert(detection.actionable);
    assert(!detection.diagnostic_only);
    assert(detection.avoidance == Avoidance::JUMP);
}

void test_builtin_and_validate() {
    using namespace hzzs;
    auto builtin = make_builtin_profile(1);
    std::string error;
    assert(validate_profile(builtin, &error));
    assert(error.empty());

    auto nan_profile = builtin;
    nan_profile.scenes[0].scene_confidence_floor = std::numeric_limits<float>::quiet_NaN();
    assert(!validate_profile(nan_profile, &error));
    assert(!error.empty());

    auto inf_profile = builtin;
    inf_profile.scenes[0].bottle_width_max = std::numeric_limits<float>::infinity();
    assert(!validate_profile(inf_profile, &error));

    auto inverted = builtin;
    inverted.scenes[0].bottle_width_min = 0.5f;
    inverted.scenes[0].bottle_width_max = 0.1f;
    assert(!validate_profile(inverted, &error));

    auto bad_schema = builtin;
    bad_schema.schema_version = 99;
    assert(!validate_profile(bad_schema, &error));
}

void test_configure_and_generation() {
    using namespace hzzs;
    auto& runtime = AlgorithmRuntime::instance();
    const auto before = runtime.generation();
    auto cfg = runtime.configure(valid_network_profile());
    assert(cfg.ok);
    assert(cfg.generation > before);
    assert(!cfg.using_builtin_fallback);
    assert(runtime.current().scenes[1].gap_width_min == 0.12f);

    auto bad = valid_network_profile();
    bad.scenes[0].ground_confidence_min = std::numeric_limits<float>::quiet_NaN();
    const auto mid = runtime.generation();
    auto failed = runtime.configure(bad);
    assert(!failed.ok);
    assert(runtime.generation() == mid);  // 失败保留旧配置
    assert(runtime.current().scenes[1].gap_width_min == 0.12f);

    auto fallback = runtime.configure_builtin("test-fallback");
    assert(fallback.ok);
    assert(fallback.using_builtin_fallback);
    assert(fallback.generation > mid);
    assert(runtime.current().is_builtin == 1);
}

void test_analyze_uses_snapshot_and_reset() {
    using namespace hzzs;
    std::vector<uint32_t> pixels(32 * 16, 0xff000000u);
    for (int y = 2; y < 6; ++y)
        for (int x = 2; x < 6; ++x) pixels[static_cast<size_t>(y) * 32 + x] = 0xffffffffu;
    for (int y = 8; y < 13; ++y)
        for (int x = 22; x < 28; ++x) pixels[static_cast<size_t>(y) * 32 + x] = 0xffffffffu;
    FrameView frame{pixels.data(), 32, 16};

    const auto parts = components(
        frame,
        [](int r, int g, int b, int, int) { return r > 240 && g > 240 && b > 240; },
        1,
        2);
    assert(parts.size() == 2);

    assert(!analyze(0, {nullptr, 0, 0}, 320, 0xFF, true, 0.185f).error.empty());
    assert(!analyze(0, frame, 100, 0xFF, true, 0.185f).error.empty());

    // 赛季边界：0/1/2 合法；-1 与 kSceneCount 及以上为 invalid scene。
    // 回归：引擎曾支持 scene=2，但 JNI 仍按双赛季拒绝，真机海盐连续失败。
    assert(analyze(-1, frame, 320, 0x7FF, true, 0.185f).error == "invalid scene");
    assert(analyze(kSceneCount, frame, 320, 0x7FF, true, 0.185f).error == "invalid scene");
    const auto sea = analyze(2, frame, 320, 0x7FF, true, 0.185f);
    assert(sea.error != "invalid scene");

    AlgorithmRuntime::instance().configure(valid_network_profile());
    const auto blank = analyze(0, frame, 320, 0xFF, true, 0.185f);
    for (const auto& d : blank.detections) assert(!d.actionable || d.kind != Kind::PLAYER);

    // generation 诊断：切换后 generation 递增；reset 不强制回退算法。
    const int64_t gen_before_reset = AlgorithmRuntime::instance().generation();
    const auto id_before = std::string(AlgorithmRuntime::instance().current().algorithm_id);
    reset();
    const int64_t gen_after_reset = AlgorithmRuntime::instance().generation();
    assert(gen_after_reset == gen_before_reset);
    assert(id_before == AlgorithmRuntime::instance().current().algorithm_id);
    AlgorithmRuntime::instance().configure_builtin("test-reset-path");
    assert(AlgorithmRuntime::instance().current().is_builtin == 1);
}

void test_concurrent_analyze_and_switch() {
    using namespace hzzs;
    std::vector<uint32_t> pixels(64 * 32, 0xff101010u);
    FrameView frame{pixels.data(), 64, 32};
    std::atomic<bool> stop{false};
    std::atomic<int> analyzes{0};

    std::thread worker([&] {
        while (!stop.load()) {
            const auto result = analyze(1, frame, 320, 0xFF, true, 0.185f);
            (void)result;
            analyzes.fetch_add(1);
        }
    });

    for (int i = 0; i < 40; ++i) {
        if (i % 2 == 0) {
            AlgorithmRuntime::instance().configure(valid_network_profile());
        } else {
            AlgorithmRuntime::instance().configure_builtin(nullptr);
        }
    }
    stop.store(true);
    worker.join();
    assert(analyzes.load() >= 0);
    // 最终状态必须是合法快照。
    std::string error;
    assert(validate_profile(AlgorithmRuntime::instance().current(), &error));
}

}  // namespace

int main() {
    test_detection_source_contract();
    test_native_sparse_respects_kind_mask_and_source();
    test_builtin_and_validate();
    test_configure_and_generation();
    test_analyze_uses_snapshot_and_reset();
    test_concurrent_analyze_and_switch();
    std::cout << "native unit tests PASS\n";
    return 0;
}
