#pragma once
#include "algorithm_runtime.h"
#include "vision_types.h"

namespace hzzs {

/**
 * 视觉引擎入口（算法引擎调度 + 历史路径 fallback）。
 *
 * - 甜品/竹影：优先 legacy_main 主路径，过弱时启发式回退。
 * - 海盐：算法引擎参数驱动路径（sea_salt_living_room）。
 *
 * 线程：JNI 层用互斥串行化 analyze / configure / reset。
 * 帧路径禁止读文件、解析 JSON、分配大型规则对象。
 */

/** 甜品工厂赛季分析（显式 params，供测试与回退路径）。 */
Result analyze_sweet(const FrameView& frame, int work_width, int enabled_kind_mask,
                     bool detect_player, float fixed_player_x_ratio,
                     const SceneAlgorithmParamsNative& params);

/** 竹影书屋赛季分析。 */
Result analyze_bamboo(const FrameView& frame, int work_width, int enabled_kind_mask,
                      bool detect_player, float fixed_player_x_ratio,
                      const SceneAlgorithmParamsNative& params);

/** 海盐客厅赛季分析（参数驱动主路径）。 */
Result analyze_sea_salt(const FrameView& frame, int work_width, int enabled_kind_mask,
                        bool detect_player, float fixed_player_x_ratio,
                        const SceneAlgorithmParamsNative& params);

/**
 * 使用当前 AlgorithmRuntime 快照分析。
 * @param scene 0=甜品，1=竹影，2=海盐（与 Kotlin SceneId 序一致）
 */
Result analyze(int scene, const FrameView& frame, int work_width, int enabled_kind_mask,
               bool detect_player, float fixed_player_x_ratio);

/** 显式传入 profile（宿主机 / 单测，不依赖进程内 runtime 单例）。 */
Result analyze_with_profile(int scene, const FrameView& frame, int work_width, int enabled_kind_mask,
                            bool detect_player, float fixed_player_x_ratio,
                            const AlgorithmRuntimeProfileNative& profile);

/** 清空引擎瞬时状态（不含算法 profile）。 */
void reset();

/** 位掩码是否启用某 Kind。 */
inline bool kind_enabled(int mask, Kind kind) {
    const int bit = static_cast<int>(kind);
    return bit >= 0 && bit < 31 && (mask & (1 << bit)) != 0;
}

}  // namespace hzzs
