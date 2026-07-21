#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace hzzs {

/**
 * Native 视觉公共类型（与 Kotlin domain.vision / ObstacleKind 对齐）。
 *
 * 坐标：Detection.bounds 使用**视口/帧归一化** [0,1]（由 vision_engine 从像素换算）。
 * Kind 枚举序与 JNI 位掩码、Kotlin ObjectKind 一致，增删须三方同步。
 */

/** 检测类别；数值参与 enabled_kind_mask 位运算。 */
enum class Kind : int32_t {
    PLAYER = 0,
    POISON_BOTTLE = 1,
    CAKE_STRUCTURE = 2,
    HANGING_SPIKE = 3,
    PIT = 4,
    PANDA_STATUE = 5,
    BAMBOO_GAP = 6,
    HANGING_BRUSH = 7,
};

/** 建议规避动作；NONE 表示不可动作或仅诊断。 */
enum class Avoidance : int32_t {
    NONE = 0,
    JUMP = 1,
    DOUBLE_JUMP = 2,
    SLIDE = 3,
};

/** 轴对齐矩形（归一化或像素，由调用约定决定）。 */
struct Rect {
    float left{};
    float top{};
    float right{};
    float bottom{};
};

/**
 * 单次检测。
 * track_hint 仅为引擎侧临时提示，跨帧稳定 ID 由 Kotlin MultiObjectTracker 分配。
 */
struct Detection {
    int32_t track_hint{};
    Kind kind{Kind::PLAYER};
    Rect bounds{};
    float confidence{};
    bool actionable{};
    bool diagnostic_only{};
    Avoidance avoidance{Avoidance::NONE};
};

/**
 * 单帧分析结果。
 * error 非空表示 fail-closed；detections 数量由上层截断。
 */
struct Result {
    float scene_confidence{};
    std::vector<Detection> detections;
    std::string error;
};

/**
 * 只读帧视图：像素指针仅在本次 analyze 调用期间有效。
 * Native 不得缓存 pixels 跨调用。
 */
struct FrameView {
    const uint32_t* pixels{};
    int width{};
    int height{};
};

}  // namespace hzzs
