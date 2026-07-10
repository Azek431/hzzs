// 火崽崽助手（HZZS）原生分析引擎 — 实现层。
//
// 这是整个分析管线的主协调器，按顺序串联所有子模块：
//   FrameDetections → [场景] → [姿态] → [跳跃] → [危险ETA] → [提示] → AnalysisResult
//
// 设计原则：
// - 单向数据流：每个子模块只读 frame，写入 result，不互相干扰
// - 场景门控：非 GroundRun 场景跳过危险检测和提示生成
// - 线程安全：此方法不修改 frame，所有状态变更仅影响内部子模块

#include "hzzs/analysis/NativeAnalysisEngine.h"

#include <algorithm>
#include <sstream>
#include <string>

namespace hzzs::analysis {

/**
 * 对单帧检测结果执行完整分析。
 *
 * 这是分析引擎的核心入口，按顺序调用所有子模块：
 * 1. 场景识别 → SceneStateMachine（确定当前场景模式）
 * 2. 角色追踪 → RunnerStateMachine（推断玩家姿态和运动状态）
 * 3. 跳跃估计 → JumpStageEstimator（估计当前跳跃阶段 0/1/2）
 * 4. 危险检测 → HazardEtaEstimator（计算 ETA，仅可分析场景）
 * 5. 提示生成 → ActionPromptEngine（输出 HUD 提示，仅可分析场景）
 * 6. 收藏物筛选 → 遍历所有对象，提取可收集物品
 * 7. UI 元数据透传 → score, hearts, shield 直接从 frame 复制到 result
 *
 * 线程安全：此方法不修改 frame，所有状态变更仅影响内部子模块。
 *
 * @param frame 当前帧的检测结果（视觉层输入）
 * @return AnalysisResult 完整的分析结果（供 HUD 渲染使用）
 */
AnalysisResult NativeAnalysisEngine::Analyze(const FrameDetections& frame) {
    AnalysisResult result{};

    // 1. 场景状态机：确定当前处于什么场景。
    result.scene_mode = scene_state_machine_.Update(frame.scene);
    result.scene_confidence = (
        result.scene_mode == SceneMode::kUnknown
            ? 0.0F
            : frame.scene.hint_confidence
    );

    // 2. 玩家姿态估计：基于帧检测 + 场景模式推断。
    result.runner = runner_state_machine_.Update(
        frame,
        result.scene_mode
    );

    // 3. 跳跃阶段估计：仅在地面跑酷模式下跟踪跳跃段数。
    result.jump_stage = jump_stage_estimator_.Update(
        result.runner,
        result.scene_mode,
        frame.timestamp_ms
    );

    // 4. 危险 ETA 估计：仅在可分析场景下执行。
    //    FlightRun 下也计算 hazards（用于 HUD 展示），但提示会被抑制。
    if (IsAnalyzableScene(result.scene_mode)) {
        result.hazards = hazard_eta_estimator_.Estimate(frame, result.runner);

        // 5. 动作提示：ActionPromptEngine 内部会根据 FlightRun/Occluded
        //    自行抑制提示，这里只需传递完整数据。
        result.prompt = action_prompt_engine_.Update(
            result.scene_mode,
            result.runner,
            result.jump_stage,
            result.hazards
        );
    } else {
        // 非可分析场景（Menu/Countdown/Result/Occluded）：清空所有提示。
        action_prompt_engine_.Reset();
    }

    // 6. 收集物提取：遍历所有检测对象，提取可收集物品。
    for (const DetectedObject& object : frame.objects) {
        if (IsCollectible(object.type) && object.bounds.IsValid()) {
            result.collectibles.push_back(object);
        }
    }

    // 7. 分数/生命/护盾等 UI 元数据透传。
    result.score = frame.score;
    result.score_confidence = frame.score_confidence;
    result.heart_count = frame.heart_count;
    result.heart_confidence = frame.heart_confidence;
    result.shield_active = frame.shield_active;
    result.shield_confidence = frame.shield_confidence;

    return result;
}

/**
 * 重置所有子模块的状态。
 *
 * 在场景切换（如从游戏回到菜单）、分析中断或初始化时调用。
 * 清除所有状态机的内部状态，确保下一帧从零开始分析。
 */
void NativeAnalysisEngine::Reset() {
    scene_state_machine_.Reset();
    runner_state_machine_.Reset();
    jump_stage_estimator_.Reset();
    action_prompt_engine_.Reset();
}

// ==================== 绘制数据序列化 ====================

/**
 * 将 AnalysisResult 序列化为 HUD 绘制数据 JSON 字符串。
 *
 * 输出格式：
 * {
 *   "scene": <int>, "scene_conf": <float>,
 *   "pose": <int>, "grounded": <bool>, "jump_stage": <int>,
 *   "prompt_action": <int>, "prompt_target": <int>,
 *   "prompt_eta_ms": <float>, "prompt_conf": <float>,
 *   "player": { "l": <float>, "t": <float>, "r": <float>, "b": <float> },
 *   "hazards": [ { "type": <int>, "eta_ms": <float>, "conf": <float>,
 *                  "action": <int>, "bounds": { ... } } ],
 *   "collectibles_count": <int>
 * }
 *
 * 所有矩形坐标均为归一化坐标（0.0 ~ 1.0），可直接用于 Canvas 绘制。
 */
std::string AnalysisResult::serializeDrawingData() const {
    std::ostringstream json;

    // === 场景与角色信息 ===
    json << "{"
         << "\"scene\":" << static_cast<int>(scene_mode)
         << ",\"scene_conf\":" << scene_confidence
         << ",\"pose\":" << static_cast<int>(runner.pose)
         << ",\"grounded\":" << (runner.grounded ? "true" : "false")
         << ",\"jump_stage\":" << static_cast<int>(jump_stage)
         << ",\"prompt_action\":" << static_cast<int>(prompt.action)
         << ",\"prompt_target\":" << static_cast<int>(prompt.target)
         << ",\"prompt_eta_ms\":" << prompt.eta_ms
         << ",\"prompt_conf\":" << prompt.confidence;

    // === 玩家矩形 ===
    json << ",\"player\":{";
    if (runner.bounds.has_value() && runner.bounds->IsValid()) {
        const auto& pb = *runner.bounds;
        json << "\"l\":" << pb.left << ",\"t\":" << pb.top
             << ",\"r\":" << pb.right << ",\"b\":" << pb.bottom;
    } else {
        json << "\"l\":0,\"t\":0,\"r\":0,\"b\":0";
    }
    json << "}";

    // === 危险物列表 ===
    json << ",\"hazards\":[";
    for (size_t i = 0; i < hazards.size(); ++i) {
        if (i > 0) json << ",";
        const auto& h = hazards[i];
        json << "{\"type\":" << static_cast<int>(h.type)
             << ",\"eta_ms\":" << h.eta_ms
             << ",\"conf\":" << h.confidence
             << ",\"action\":" << static_cast<int>(h.preferred_action)
             << ",\"bounds\":{";
        if (h.danger_bounds.IsValid()) {
            const auto& hb = h.danger_bounds;
            json << "\"l\":" << hb.left << ",\"t\":" << hb.top
                 << ",\"r\":" << hb.right << ",\"b\":" << hb.bottom;
        } else {
            json << "\"l\":0,\"t\":0,\"r\":0,\"b\":0";
        }
        json << "}}";
    }
    json << "]";

    // === 收藏物数量 ===
    json << ",\"collectibles_count\":" << collectibles.size();

    json << "}";
    return json.str();
}

}  // namespace hzzs::analysis
