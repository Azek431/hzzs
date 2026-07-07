#pragma once

#include "hzzs/analysis/ActionPromptEngine.h"
#include "hzzs/analysis/HazardEtaEstimator.h"
#include "hzzs/analysis/JumpStageEstimator.h"
#include "hzzs/analysis/RunnerStateMachine.h"
#include "hzzs/analysis/SceneStateMachine.h"

namespace hzzs::analysis {

/**
 * HZZS C++ 分析核心。
 *
 * 这是整个分析引擎的入口类，协调以下子模块完成单帧分析：
 * 1. SceneStateMachine → 确定当前场景模式
 * 2. RunnerStateMachine → 推断玩家角色姿态和运动状态
 * 3. JumpStageEstimator → 估计当前跳跃阶段（0/1/2）
 * 4. HazardEtaEstimator → 计算所有危险物的 ETA 和推荐动作
 * 5. ActionPromptEngine → 综合输出最终的 HUD 提示
 *
 * 输入：FrameDetections（视觉层每帧输出的检测结果）
 * 输出：AnalysisResult（完整的分析结果，包含场景、姿态、危险、提示等）
 *
 * 数据处理流：
 * FrameDetections → [场景识别] → SceneMode
 *                              → [角色追踪] → RunnerMotion
 *                              → [跳跃估计] → jump_stage
 *                              → [危险检测] → hazards[] (仅地面模式)
 *                              → [提示生成] → ActionPrompt (仅地面模式)
 *                              → [收藏物筛选] → collectibles[]
 *
 * 注意：此引擎不关心数据如何获取（屏幕采集/网络传输/本地文件），
 * 只负责将 FrameDetections 转换为 AnalysisResult。
 */
class NativeAnalysisEngine {
public:
    /**
     * 对单帧检测结果执行完整分析。
     *
     * 按顺序调用所有子模块，输出完整的 AnalysisResult。
     * 如果场景不是地面跑酷，则跳过危险检测和提示生成。
     *
     * @param frame 当前帧的检测结果
     * @return AnalysisResult 完整的分析结果
     */
    AnalysisResult Analyze(const FrameDetections& frame);

    /**
     * 重置所有子模块的状态。
     *
     * 在场景切换（如从游戏回到菜单）、分析中断或初始化时调用。
     * 清除所有状态机的内部状态，确保下一帧从零开始分析。
     */
    void Reset();

private:
    SceneStateMachine scene_state_machine_{};
    RunnerStateMachine runner_state_machine_{};
    JumpStageEstimator jump_stage_estimator_{};
    HazardEtaEstimator hazard_eta_estimator_{};
    ActionPromptEngine action_prompt_engine_{};
};

}  // namespace hzzs::analysis
