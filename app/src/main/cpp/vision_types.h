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

/** 检测类别；数值参与 enabled_kind_mask 位运算，与 Kotlin ObjectKind.ordinal 对齐。 */
enum class Kind : int32_t {
    PLAYER = 0,
    GREEN_BOTTLE = 1,
    CAKE_STRUCTURE = 2,
    HANGING_SPIKE = 3,
    PIT = 4,
    PANDA_STATUE = 5,
    BAMBOO_GAP = 6,
    HANGING_BRUSH = 7,
    SAND_CASTLE = 8,
    HANGING_ANCHOR = 9,
    SEA_PIT = 10,
};

/** 建议规避动作；NONE 表示不可动作或仅诊断。 */
enum class Avoidance : int32_t {
    NONE = 0,
    JUMP = 1,
    DOUBLE_JUMP = 2,
    SLIDE = 3,
    PRESS = 4,
    SWIPE_UP = 5,
};

/** 检测来源标识；用于区分不同算法路径产出的检测。 */
enum class DetectionSource : int32_t {
    DEFAULT_HEURISTIC = 0,  // 启发式主路径 (default backend)
    MULTICOLOR = 1,         // 多点找色检测
    SOY_EXACT = 2,          // Exact模式检测
    SEA_FAST = 3,           // Fast模式检测
    NATIVE_SPARSE = 4,      // Native Vision稀疏检测
};

/** 轴对齐矩形（归一化或像素，由调用约定决定）。 */
struct Rect {
    float left{};
    float top{};
    float right{};
    float bottom{};
};

/**
 * 各阶段耗时（纳秒）。仅供开发者诊断与排障；默认关闭，不影响帧路径热性能。
 * 全部由 analyze 调用方在各阶段边界采样；未启用时为全 0。
 *
 * @property jni_prep_ns  JNI 边界耗时（像素 pin + 视口裁剪，在 jni_bridge 内采样）
 * @property detect_ns    主路径场景检测（sweet/bamboo/sea_salt 主检测器）
 * @property postfilter_ns 尺寸窗后过滤 + 启发式回退决策
 * @property finalize_ns   坐标归一化 + 身后障碍清洗
 */
struct StageTiming {
    int64_t jni_prep_ns{0};
    int64_t detect_ns{0};
    int64_t postfilter_ns{0};
    int64_t finalize_ns{0};
    int64_t total_ns() const { return jni_prep_ns + detect_ns + postfilter_ns + finalize_ns; }
};

/**
 * 单次检测。
 * track_hint 仅为引擎侧临时提示，跨帧稳定 ID 由 Kotlin MultiObjectTracker 分配。
 */
struct Detection {
    int32_t track_hint{};
    DetectionSource source{DetectionSource::DEFAULT_HEURISTIC};  // 新增：检测来源
    Kind kind{Kind::PLAYER};
    Rect bounds{};
    float confidence{};
    bool actionable{};
    bool diagnostic_only{};
    Avoidance avoidance{Avoidance::NONE};
};

/** 多点找色单模板匹配诊断；默认填充占位，仅开发者诊断开关开启时有效。 */
enum class MulticolorRejectReason : int32_t {
    NONE = 0,
    SEARCH_REGION_INVALID = 1,
    OFFSET_OUT_OF_BOUNDS = 2,
    BASE_MISMATCH = 3,
    OFFSET_MISMATCH = 4,
    KIND_DISABLED = 5,
};

struct MulticolorDiag {
    int32_t pattern_index{-1};
    bool matched{false};
    int32_t base_x{0};
    int32_t base_y{0};
    float threshold_used{0.0f};
    MulticolorRejectReason reason{MulticolorRejectReason::NONE};
    /** 搜索区（帧归一化），供 HUD 绘制搜索范围矩形。 */
    float search_left{0.0f};
    float search_top{0.0f};
    float search_right{0.0f};
    float search_bottom{0.0f};
};

/**
 * 单帧分析结果。
 * error 非空表示 fail-closed；detections 数量由上层截断。
 * timing / filtered_out / multicolor_diag 默认填充占位，仅开发者诊断开关开启时有效。
 *
 * @property timing          各阶段耗时（纳秒），见 [StageTiming]
 * @property filtered_out    被尺寸窗剔除的障碍 + 原因，供开发者定位误过滤
 * @property multicolor_diag 多点找色各模板命中/拒绝明细
 */

/** 过滤剔除原因；与 [FilteredDetection] 配套。 */
enum class FilterReason : int32_t {
    SIZE_WIDTH_MIN = 0,
    SIZE_WIDTH_MAX = 1,
    SIZE_HEIGHT_MIN = 2,
    SIZE_HEIGHT_MAX = 3,
    BEHIND_PLAYER = 4,
    KIND_DISABLED = 5,
};

/** 被尺寸窗剔除的检测 + 原因，供开发者诊断（默认关闭，不参与规划）。 */
struct FilteredDetection {
    Detection detection;
    FilterReason reason{};
};

struct Result {
    float scene_confidence{};
    std::vector<Detection> detections;
    StageTiming timing;
    std::vector<FilteredDetection> filtered_out;
    std::vector<MulticolorDiag> multicolor_diag;
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
