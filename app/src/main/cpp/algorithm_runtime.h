#pragma once

/**
 * 声明式算法运行时（CC-1）Native 侧。
 *
 * 与 Kotlin domain.vision.AlgorithmRuntimeProfile 对齐：
 * - configure 时整份校验并替换不可变快照，generation++
 * - analyze 只读当前 generation，不在帧路径解析 JSON
 * - 不得包含手势 / Root / 包白名单等安全门禁字段
 * - 失败回退 builtin.hzzs.base（version 0.1.0）
 */

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>

namespace hzzs {

/** 与 Kotlin AlgorithmRuntimeProfile.SCHEMA_VERSION 对齐。 */
constexpr int32_t kAlgorithmSchemaVersion = 1;
/** 与 Kotlin SceneId.entries.size 对齐：甜品 / 竹影 / 海盐。 */
constexpr int32_t kSceneCount = 3;
constexpr int32_t kMaxAlgorithmIdLen = 64;
constexpr int32_t kMaxAlgorithmVersionLen = 32;

struct SceneColorThresholdsNative {
    int32_t bottle_green_min{72};
    float bottle_green_over_red{1.08f};
    float bottle_green_over_blue{1.18f};
    int32_t bottle_red_max{170};
    int32_t cake_red_min{145};
    int32_t cake_green_min{92};
    int32_t cake_blue_max{190};
    int32_t spike_red_min{150};
    int32_t spike_blue_min{88};
    float spike_red_over_green{1.14f};
    int32_t bamboo_green_min{80};
    float bamboo_green_over_red{0.78f};
    float bamboo_green_over_blue{1.25f};
    int32_t bamboo_blue_max{125};
    int32_t brush_dark_max{94};
    int32_t statue_chroma_max{48};
};

struct SceneAlgorithmParamsNative {
    float scene_confidence_floor{0.92f};
    float player_confidence_floor{0.45f};
    float fixed_player_top{0.72f};
    float fixed_player_bottom{0.94f};
    int32_t fixed_player_width_divisor{20};
    float fallback_scene_confidence_max{0.20f};
    int32_t fallback_max_detections{1};
    float ground_search_top{0.50f};
    float ground_search_bottom{0.82f};
    float ground_confidence_min{0.32f};
    float bottle_width_min{0.028f};
    float bottle_width_max{0.19f};
    float bottle_height_min{0.045f};
    float bottle_height_max{0.28f};
    float cake_width_min{0.105f};
    float cake_width_max{0.60f};
    float cake_height_min{0.10f};
    float cake_wide_width_ratio{0.22f};
    float statue_width_min{0.05f};
    float statue_width_max{0.34f};
    float statue_height_min{0.075f};
    float statue_height_max{0.35f};
    float gap_width_min{0.135f};
    float gap_width_max{0.78f};
    float gap_height_min{0.11f};
    float gap_wide_width_ratio{0.22f};
    float brush_width_min{0.032f};
    float brush_width_max{0.23f};
    float brush_height_min{0.10f};
    float brush_height_max{0.54f};
    float spike_width_min{0.09f};
    float spike_width_max{0.42f};
    float spike_height_min{0.16f};
    float spike_height_max{0.54f};
    SceneColorThresholdsNative colors{};
};

/**
 * 不可变算法运行时快照。configure 成功后整份替换；analyze 只读当前 generation。
 * 不包含手势、Root、包名白名单等安全门禁字段。
 */
struct AlgorithmRuntimeProfileNative {
    char algorithm_id[kMaxAlgorithmIdLen + 1]{"builtin.hzzs.v1"};
    char version[kMaxAlgorithmVersionLen + 1]{"1.0.0"};
    int32_t schema_version{kAlgorithmSchemaVersion};
    int32_t is_builtin{1};
    int64_t generation{1};
    SceneAlgorithmParamsNative scenes[kSceneCount]{};
};

struct AlgorithmConfigResult {
    bool ok{false};
    int64_t generation{0};
    bool using_builtin_fallback{true};
    std::string error;
};

/** 内置 profile：固化当前识别行为。 */
AlgorithmRuntimeProfileNative make_builtin_profile(int64_t generation);

/** 校验并规范化；失败时 error 非空。 */
bool validate_profile(const AlgorithmRuntimeProfileNative& profile, std::string* error);

/**
 * 算法运行时单例：互斥切换，analyze 持共享读锁语义（unique_lock 保护指针交换）。
 * 切换时重置内部 generation；不允许分析中半热切换（调用方须在安全点调用 configure）。
 */
class AlgorithmRuntime {
public:
    static AlgorithmRuntime& instance();

    AlgorithmConfigResult configure(const AlgorithmRuntimeProfileNative& candidate);
    AlgorithmConfigResult configure_builtin(const char* reason);
    void reset();

    /** 返回当前快照副本；generation 用于诊断。 */
    AlgorithmRuntimeProfileNative snapshot() const;
    int64_t generation() const;

    /** 分析路径：获取当前只读指针（在 analyze 期间由 mutex 保护拷贝）。 */
    AlgorithmRuntimeProfileNative current() const;

private:
    AlgorithmRuntime();

    mutable std::mutex mutex_;
    AlgorithmRuntimeProfileNative active_;
    std::atomic<int64_t> generation_{1};
};

}  // namespace hzzs
