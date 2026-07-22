/**
 * 算法运行时：内置 profile、严格校验、进程内单例切换。
 *
 * 与 Kotlin AlgorithmProfileValidator 范围策略保持一致；
 * 校验失败不得替换当前快照（由 AlgorithmRuntime::configure 保证）。
 */
#include "algorithm_runtime.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace hzzs {
namespace {

bool finite_unit(float value) {
    return std::isfinite(value) && value >= 0.0f && value <= 1.0f;
}

bool finite_in(float value, float lo, float hi) {
    return std::isfinite(value) && value >= lo && value <= hi;
}

bool channel_ok(int32_t value) {
    return value >= 0 && value <= 255;
}

void copy_cstr(char* dest, std::size_t dest_size, const char* src) {
    if (dest_size == 0) return;
    if (!src) {
        dest[0] = '\0';
        return;
    }
    std::strncpy(dest, src, dest_size - 1);
    dest[dest_size - 1] = '\0';
}

bool id_char_ok(char c, bool first) {
    if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
        return true;
    }
    if (first) return false;
    return c == '.' || c == '_' || c == '-';
}

bool version_char_ok(char c, bool first) {
    if (id_char_ok(c, first)) return true;
    if (first) return false;
    return c == '+';
}

bool validate_identifier(const char* text, int max_len, bool (*char_ok)(char, bool), std::string* error,
                         const char* field) {
    if (!text || text[0] == '\0') {
        if (error) *error = std::string(field) + " empty";
        return false;
    }
    const std::size_t len = std::strlen(text);
    if (len > static_cast<std::size_t>(max_len)) {
        if (error) *error = std::string(field) + " too long";
        return false;
    }
    for (std::size_t i = 0; i < len; ++i) {
        if (!char_ok(text[i], i == 0)) {
            if (error) *error = std::string(field) + " invalid chars";
            return false;
        }
    }
    return true;
}

bool validate_scene(int scene, const SceneAlgorithmParamsNative& p, std::string* error) {
    auto fail = [&](const char* msg) {
        if (error) *error = std::string("scene") + std::to_string(scene) + ": " + msg;
        return false;
    };
    if (!finite_unit(p.scene_confidence_floor)) return fail("scene_confidence_floor");
    if (!finite_unit(p.player_confidence_floor)) return fail("player_confidence_floor");
    if (!finite_unit(p.fixed_player_top) || !finite_unit(p.fixed_player_bottom) ||
        p.fixed_player_top >= p.fixed_player_bottom) {
        return fail("fixed_player_top/bottom");
    }
    if (p.fixed_player_width_divisor < 8 || p.fixed_player_width_divisor > 64) {
        return fail("fixed_player_width_divisor");
    }
    if (!finite_unit(p.fallback_scene_confidence_max)) return fail("fallback_scene_confidence_max");
    if (p.fallback_max_detections < 0 || p.fallback_max_detections > 8) {
        return fail("fallback_max_detections");
    }
    if (!finite_unit(p.ground_search_top) || !finite_unit(p.ground_search_bottom) ||
        p.ground_search_top >= p.ground_search_bottom) {
        return fail("ground_search");
    }
    if (!finite_unit(p.ground_confidence_min)) return fail("ground_confidence_min");

    auto ordered = [&](float a, float b, const char* name) {
        if (!std::isfinite(a) || !std::isfinite(b) || a > b) {
            return fail(name);
        }
        return true;
    };
    if (!ordered(p.bottle_width_min, p.bottle_width_max, "bottle_width") ||
        !finite_in(p.bottle_width_min, 0.001f, 0.5f) || !finite_in(p.bottle_width_max, 0.01f, 0.8f)) {
        return false;
    }
    if (!ordered(p.bottle_height_min, p.bottle_height_max, "bottle_height") ||
        !finite_in(p.bottle_height_min, 0.001f, 0.6f) || !finite_in(p.bottle_height_max, 0.01f, 0.8f)) {
        return false;
    }
    if (!ordered(p.cake_width_min, p.cake_width_max, "cake_width") ||
        !finite_in(p.cake_width_min, 0.01f, 0.7f) || !finite_in(p.cake_width_max, 0.05f, 0.95f) ||
        !finite_in(p.cake_height_min, 0.01f, 0.8f) || !finite_in(p.cake_wide_width_ratio, 0.05f, 0.8f)) {
        return false;
    }
    if (!ordered(p.statue_width_min, p.statue_width_max, "statue_width") ||
        !finite_in(p.statue_width_min, 0.01f, 0.5f) || !finite_in(p.statue_width_max, 0.05f, 0.8f) ||
        !ordered(p.statue_height_min, p.statue_height_max, "statue_height") ||
        !finite_in(p.statue_height_min, 0.01f, 0.6f) || !finite_in(p.statue_height_max, 0.05f, 0.8f)) {
        return false;
    }
    if (!ordered(p.gap_width_min, p.gap_width_max, "gap_width") ||
        !finite_in(p.gap_width_min, 0.01f, 0.8f) || !finite_in(p.gap_width_max, 0.05f, 0.95f) ||
        !finite_in(p.gap_height_min, 0.01f, 0.8f) || !finite_in(p.gap_wide_width_ratio, 0.05f, 0.8f)) {
        return false;
    }
    if (!ordered(p.brush_width_min, p.brush_width_max, "brush_width") ||
        !finite_in(p.brush_width_min, 0.005f, 0.5f) || !finite_in(p.brush_width_max, 0.02f, 0.8f) ||
        !ordered(p.brush_height_min, p.brush_height_max, "brush_height") ||
        !finite_in(p.brush_height_min, 0.01f, 0.7f) || !finite_in(p.brush_height_max, 0.05f, 0.9f)) {
        return false;
    }
    if (!ordered(p.spike_width_min, p.spike_width_max, "spike_width") ||
        !finite_in(p.spike_width_min, 0.01f, 0.6f) || !finite_in(p.spike_width_max, 0.05f, 0.9f) ||
        !ordered(p.spike_height_min, p.spike_height_max, "spike_height") ||
        !finite_in(p.spike_height_min, 0.01f, 0.7f) || !finite_in(p.spike_height_max, 0.05f, 0.9f)) {
        return false;
    }

    const auto& c = p.colors;
    if (!channel_ok(c.bottle_green_min) || !channel_ok(c.bottle_red_max) || !channel_ok(c.cake_red_min) ||
        !channel_ok(c.cake_green_min) || !channel_ok(c.cake_blue_max) || !channel_ok(c.spike_red_min) ||
        !channel_ok(c.spike_blue_min) || !channel_ok(c.bamboo_green_min) || !channel_ok(c.bamboo_blue_max) ||
        !channel_ok(c.brush_dark_max) || !channel_ok(c.statue_chroma_max)) {
        return fail("color channel");
    }
    if (!finite_in(c.bottle_green_over_red, 0.5f, 3.0f) ||
        !finite_in(c.bottle_green_over_blue, 0.5f, 3.0f) ||
        !finite_in(c.spike_red_over_green, 0.5f, 3.0f) ||
        !finite_in(c.bamboo_green_over_red, 0.2f, 3.0f) ||
        !finite_in(c.bamboo_green_over_blue, 0.5f, 4.0f)) {
        return fail("color ratio");
    }
    return true;
}

SceneAlgorithmParamsNative sweet_builtin_params() {
    SceneAlgorithmParamsNative p{};
    p.scene_confidence_floor = 0.92f;
    p.player_confidence_floor = 0.45f;
    p.fixed_player_top = 0.72f;
    p.fixed_player_bottom = 0.94f;
    p.fixed_player_width_divisor = 20;
    p.fallback_scene_confidence_max = 0.20f;
    p.fallback_max_detections = 1;
    p.ground_search_top = 0.50f;
    p.ground_search_bottom = 0.82f;
    p.ground_confidence_min = 0.32f;
    return p;
}

SceneAlgorithmParamsNative bamboo_builtin_params() {
    SceneAlgorithmParamsNative p = sweet_builtin_params();
    p.scene_confidence_floor = 0.82f;
    p.ground_search_top = 0.52f;
    p.ground_confidence_min = 0.28f;
    return p;
}

SceneAlgorithmParamsNative sea_salt_builtin_params() {
    SceneAlgorithmParamsNative p = sweet_builtin_params();
    p.scene_confidence_floor = 0.80f;
    p.fixed_player_width_divisor = 18;
    p.ground_search_top = 0.54f;
    p.ground_search_bottom = 0.84f;
    p.ground_confidence_min = 0.26f;
    p.statue_width_max = 0.40f;
    p.statue_height_max = 0.42f;
    p.gap_width_min = 0.12f;
    p.gap_width_max = 0.80f;
    p.gap_wide_width_ratio = 0.24f;
    p.brush_width_min = 0.04f;
    p.brush_width_max = 0.28f;
    p.brush_height_max = 0.58f;
    p.colors.cake_blue_max = 170;
    p.colors.brush_dark_max = 100;
    p.colors.statue_chroma_max = 55;
    return p;
}

}  // namespace

AlgorithmRuntimeProfileNative make_builtin_profile(int64_t generation) {
    AlgorithmRuntimeProfileNative profile{};
    copy_cstr(profile.algorithm_id, sizeof(profile.algorithm_id), "builtin.hzzs.base");
    // 与 Kotlin AlgorithmRuntimeProfile.BUILTIN_VERSION / AlgorithmIds.BUILTIN_VERSION 对齐。
    copy_cstr(profile.version, sizeof(profile.version), "0.1.0");
    profile.schema_version = kAlgorithmSchemaVersion;
    profile.is_builtin = 1;
    profile.generation = generation;
    profile.scenes[0] = sweet_builtin_params();
    profile.scenes[1] = bamboo_builtin_params();
    profile.scenes[2] = sea_salt_builtin_params();
    return profile;
}

bool validate_profile(const AlgorithmRuntimeProfileNative& profile, std::string* error) {
    if (profile.schema_version != kAlgorithmSchemaVersion) {
        if (error) *error = "unsupported schema_version";
        return false;
    }
    if (!validate_identifier(profile.algorithm_id, kMaxAlgorithmIdLen, id_char_ok, error, "algorithm_id")) {
        return false;
    }
    if (!validate_identifier(profile.version, kMaxAlgorithmVersionLen, version_char_ok, error, "version")) {
        return false;
    }
    if (profile.generation <= 0) {
        if (error) *error = "generation must be positive";
        return false;
    }
    for (int scene = 0; scene < kSceneCount; ++scene) {
        if (!validate_scene(scene, profile.scenes[scene], error)) return false;
    }
    return true;
}

AlgorithmRuntime& AlgorithmRuntime::instance() {
    static AlgorithmRuntime runtime;
    return runtime;
}

AlgorithmRuntime::AlgorithmRuntime() : active_(make_builtin_profile(1)) {
    generation_.store(1, std::memory_order_relaxed);
}

AlgorithmConfigResult AlgorithmRuntime::configure(const AlgorithmRuntimeProfileNative& candidate) {
    std::lock_guard<std::mutex> lock(mutex_);
    AlgorithmConfigResult result;
    std::string error;
    AlgorithmRuntimeProfileNative next = candidate;
    if (!validate_profile(next, &error)) {
        result.ok = false;
        result.generation = active_.generation;
        result.using_builtin_fallback = active_.is_builtin != 0;
        result.error = error;
        return result;
    }
    const int64_t gen = generation_.fetch_add(1, std::memory_order_relaxed) + 1;
    next.generation = gen;
    active_ = next;
    result.ok = true;
    result.generation = gen;
    result.using_builtin_fallback = next.is_builtin != 0;
    return result;
}

AlgorithmConfigResult AlgorithmRuntime::configure_builtin(const char* reason) {
    std::lock_guard<std::mutex> lock(mutex_);
    const int64_t gen = generation_.fetch_add(1, std::memory_order_relaxed) + 1;
    active_ = make_builtin_profile(gen);
    AlgorithmConfigResult result;
    result.ok = true;
    result.generation = gen;
    result.using_builtin_fallback = true;
    if (reason && reason[0] != '\0') result.error = reason;
    return result;
}

void AlgorithmRuntime::reset() {
    // 保留当前算法快照；检测器本身无跨帧状态。算法回退请调用 configure_builtin。
}

AlgorithmRuntimeProfileNative AlgorithmRuntime::snapshot() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return active_;
}

int64_t AlgorithmRuntime::generation() const {
    return generation_.load(std::memory_order_relaxed);
}

AlgorithmRuntimeProfileNative AlgorithmRuntime::current() const {
    return snapshot();
}

}  // namespace hzzs
