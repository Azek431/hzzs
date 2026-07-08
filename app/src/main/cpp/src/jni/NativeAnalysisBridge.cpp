// 火崽崽助手（HZZS）JNI 桥接层 — C++ 分析引擎与 Kotlin UI 层的桥梁。
//
// 此文件包含三个 JNI 导出函数：
// 1. nativeAnalyzeFrame — 分析单帧数据，返回 JSON 格式的 AnalysisResult
// 2. nativeGetEngineInfo — 返回引擎版本和特性描述
// 3. nativeRunSelfCheck — 执行自检程序，验证引擎核心逻辑正确性
// 4. nativeResetEngine — 重置引擎状态机（停止分析时调用）
//
// 关键设计决策：
// - 使用 static 引擎实例（nativeAnalyzeFrame 中），确保跨帧状态持久化
//   这对于 SceneStateMachine 的连续确认机制至关重要
// - JSON 序列化采用手动拼接而非 nlohmann/json 库，减少 APK 体积
// - 所有浮点数直接输出为原生精度，Kotlin 端通过正则提取

#include <jni.h>
#include <android/log.h>
#include <sstream>
#include <string>

#include "hzzs/analysis/NativeAnalysisEngine.h"

namespace {

/** 日志标签，用于 Android logcat 中过滤 HZZS 相关日志 */
constexpr const char* kLogTag = "HZZS-Native";

/** 参数数量常量：nativeAnalyzeFrame 共 15 个参数（timestamp + 4 player + 6 hazard + 2 scroll） */
constexpr int kAnalyzeParamCount = 15;

/**
 * 将 C++ std::string 转换为 JNI jstring。
 *
 * 使用 NewStringUTF 将 UTF-8 编码的 C++ 字符串转换为 JNI jstring。
 * 注意：NewStringUTF 期望合法的 UTF-8 序列，遇到非法序列时会抛出
 * IllegalArgumentException。当前所有调用方的字符串均为 ASCII，
 * 不涉及多字节字符，因此此实现是安全的。
 *
 * @param env JNI 环境指针
 * @param value C++ 字符串
 * @return 转换后的 jstring
 */
jstring ToJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

/**
 * JNI 导出方法：分析单帧模拟数据并返回结构化结果。
 *
 * 预留接口：当前 Kotlin 端尚未调用此方法。接入屏幕采集后，Kotlin 端需
 * 添加对应的 external fun analyzeFrame(...) 并通过 NativeAnalysisBridge
 * 暴露给上层 UI 调用。
 *
 * 使用静态持久化引擎实例，确保多帧确认机制正常工作（场景切换、动作提示稳定）。
 *
 * 参数说明：
 * - timestamp_ms：帧时间戳（毫秒）
 * - player_left/top/right/bottom：玩家矩形归一化坐标
 * - player_confidence：玩家检测可信度
 * - hazard_type：危险物类型（0=无，1=蛋糕断层，2=毒瓶，3=裱花袋）
 * - hazard_left/top/right/bottom：危险物归一化坐标
 * - hazard_confidence：危险物检测可信度
 * - hazard_velocity_x：危险物 X 方向速度
 * - world_scroll_speed：背景滚动速度
 *
 * @param env JNI 环境指针
 * @param obj JNI 对象（此方法不需要，保留占位）
 * @return JSON 格式的 AnalysisResult 字符串
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_top_azek431_hzzs_NativeAnalysisBridge_nativeAnalyzeFrame(
    JNIEnv* env,
    jobject,
    jlong timestamp_ms,
    jfloat player_left,
    jfloat player_top,
    jfloat player_right,
    jfloat player_bottom,
    jfloat player_confidence,
    jint hazard_type,
    jfloat hazard_left,
    jfloat hazard_top,
    jfloat hazard_right,
    jfloat hazard_bottom,
    jfloat hazard_confidence,
    jfloat hazard_velocity_x,
    jfloat world_scroll_speed
) {
    // 使用静态引擎实例，跨帧保持状态（场景确认、姿态基线、跳跃阶段等）
    static hzzs::analysis::NativeAnalysisEngine engine{};

    // 构造帧数据
    hzzs::analysis::FrameDetections frame{};
    frame.timestamp_ms = static_cast<std::int64_t>(timestamp_ms);

    // 场景模式：固定为地面跑酷
    frame.scene.hint = hzzs::analysis::SceneMode::kGroundRun;
    frame.scene.hint_confidence = 0.98F;

    // 玩家边界
    if (player_right > player_left && player_bottom > player_top) {
        frame.player_bounds = hzzs::analysis::RectF{
            player_left, player_top, player_right, player_bottom
        };
    }
    frame.player_confidence = player_confidence;
    frame.world_scroll_speed_x_per_second = world_scroll_speed;

    // 危险物
    if (hazard_type > 0 && hazard_right > hazard_left && hazard_bottom > hazard_top) {
        hzzs::analysis::DetectedObject hazard{};
        hazard.type = static_cast<hzzs::analysis::GameObjectType>(hazard_type);
        hazard.bounds = hzzs::analysis::RectF{
            hazard_left, hazard_top, hazard_right, hazard_bottom
        };
        hazard.danger_bounds = hazard.bounds;
        hazard.confidence = hazard_confidence;
        hazard.track_id = 1;
        hazard.velocity_x_per_second = hazard_velocity_x;
        frame.objects.push_back(hazard);
    }

    // 执行分析
    const hzzs::analysis::AnalysisResult result = engine.Analyze(frame);

    // 序列化结果为 JSON 字符串
    std::ostringstream json;
    json << "{\"scene_mode\":" << static_cast<int>(result.scene_mode)
         << ",\"scene_confidence\":" << result.scene_confidence
         << ",\"runner_pose\":" << static_cast<int>(result.runner.pose)
         << ",\"runner_grounded\":" << (result.runner.grounded ? "true" : "false")
         << ",\"jump_stage\":" << static_cast<int>(result.jump_stage)
         << ",\"prompt_action\":" << static_cast<int>(result.prompt.action)
         << ",\"prompt_target\":" << static_cast<int>(result.prompt.target)
         << ",\"prompt_eta_ms\":" << result.prompt.eta_ms
         << ",\"prompt_confidence\":" << result.prompt.confidence
         << ",\"hazards_count\":" << result.hazards.size();

    // 添加每个危险物的 ETA
    json << ",\"hazards\":[";
    for (size_t i = 0; i < result.hazards.size(); ++i) {
        if (i > 0) json << ",";
        json << "{\"type\":" << static_cast<int>(result.hazards[i].type)
             << ",\"eta_ms\":" << result.hazards[i].eta_ms
             << ",\"confidence\":" << result.hazards[i].confidence
             << ",\"action\":" << static_cast<int>(result.hazards[i].preferred_action)
             << ",\"jump_stage\":" << static_cast<int>(result.hazards[i].required_jump_stage)
             << "}";
    }
    json << "]";

    // 添加收藏物数量
    json << ",\"collectibles_count\":" << result.collectibles.size()
         << "}";

    std::string output = json.str();

    __android_log_print(
        ANDROID_LOG_DEBUG,
        kLogTag,
        "[JNI] analyzed frame ts=%lld -> %s",
        static_cast<long long>(timestamp_ms),
        output.c_str()
    );

    return ToJString(env, output);
}

/**
 * 创建一个模拟的地面跑酷帧数据，用于自检。
 *
 * 构造的帧包含：
 * - 场景模式：kGroundRun（置信度 0.98）
 * - 玩家矩形：归一化坐标 (0.14, 0.66, 0.24, 0.84)，置信度 0.96
 * - 背景滚动速度：-0.45（世界向左滚动）
 * - 一个蛋糕断层对象：位于玩家前方 (0.52, 0.78, 0.66, 0.98)，置信度 0.93
 *
 * 此帧数据模拟了玩家在跑酷过程中接近一个蛋糕断面的典型场景。
 * 自检程序通过对此帧进行分析，验证引擎能否正确识别断层并输出跳跃提示。
 *
 * @param timestamp_ms 帧时间戳（毫秒），用于帧间时序计算
 * @return 构造好的 FrameDetections 对象
 */
hzzs::analysis::FrameDetections CreateGroundFrame(
    std::int64_t timestamp_ms
) {
    hzzs::analysis::FrameDetections frame{};
    frame.timestamp_ms = timestamp_ms;
    frame.scene.hint = hzzs::analysis::SceneMode::kGroundRun;
    frame.scene.hint_confidence = 0.98F;
    frame.player_bounds = hzzs::analysis::RectF{
        0.14F,
        0.66F,
        0.24F,
        0.84F,
    };
    frame.player_confidence = 0.96F;
    frame.world_scroll_speed_x_per_second = -0.45F;

    hzzs::analysis::DetectedObject gap{};
    gap.type = hzzs::analysis::GameObjectType::kCakeGap;
    gap.bounds = hzzs::analysis::RectF{
        0.52F,
        0.78F,
        0.66F,
        0.98F,
    };
    gap.danger_bounds = gap.bounds;
    gap.confidence = 0.93F;
    gap.track_id = 1;
    gap.velocity_x_per_second = -0.45F;
    frame.objects.push_back(gap);

    return frame;
}

}  // namespace

/**
 * JNI 导出方法：获取引擎信息字符串。
 *
 * 由 Kotlin 端 NativeAnalysisBridge.engineInfo() 调用。
 * 返回引擎名称、C++ 标准和功能列表的描述字符串。
 *
 * @param env JNI 环境指针
 * @param obj JNI 对象（此方法不需要，保留占位）
 * @return 引擎信息字符串
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_top_azek431_hzzs_NativeAnalysisBridge_nativeGetEngineInfo(
    JNIEnv* env,
    jobject
) {
    const std::string message =
        "HZZS native core ready | C++17 | "
        "scene + runner + double-jump + hazard ETA";

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "%s",
        message.c_str()
    );

    return ToJString(env, message);
}

/**
 * JNI 导出方法：执行引擎自检。
 *
 * 由 Kotlin 端 NativeAnalysisBridge.runSelfCheck() 调用。
 * 自检流程：
 * 1. 创建 NativeAnalysisEngine 实例
 * 2. 注入两帧模拟地面跑酷数据（时间戳 16ms 和 32ms）
 * 3. 分析第二帧，验证以下断言：
 *    - scene_mode == kGroundRun（场景识别正确）
 *    - runner.pose == kRun（角色姿态正确）
 *    - prompt.action == kJump（跳跃提示正确）
 *    - jump_stage == 0（跳跃阶段正确，尚未起跳）
 *
 * 如果所有断言通过，返回 "PASS: ..."；否则返回 "FAIL: ..."。
 *
 * @param env JNI 环境指针
 * @param obj JNI 对象（此方法不需要，保留占位）
 * @return 自检结果字符串
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_top_azek431_hzzs_NativeAnalysisBridge_nativeRunSelfCheck(
    JNIEnv* env,
    jobject
) {
    hzzs::analysis::NativeAnalysisEngine engine{};

    // 第一帧：初始化基线（不检查第一帧结果）
    engine.Analyze(CreateGroundFrame(16));

    // 第二帧：验证分析结果
    const hzzs::analysis::AnalysisResult result = engine.Analyze(
        CreateGroundFrame(32)
    );

    // 验证四个关键断言
    const bool passed = (
        result.scene_mode == hzzs::analysis::SceneMode::kGroundRun &&
        result.runner.pose == hzzs::analysis::RunnerPose::kRun &&
        result.prompt.action == hzzs::analysis::PromptAction::kJump &&
        result.jump_stage == 0
    );

    const std::string message = passed
        ? "PASS: scene, runner, hazard ETA and jump prompt self-check."
        : "FAIL: native analysis self-check.";

    __android_log_print(
        passed ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
        kLogTag,
        "%s",
        message.c_str()
    );

    return ToJString(env, message);
}

/**
 * JNI 导出方法：重置分析引擎状态。
 *
 * 在"结束执行"时调用，清除所有子模块的状态机。
 */
extern "C"
JNIEXPORT void JNICALL
Java_top_azek431_hzzs_NativeAnalysisBridge_nativeResetEngine(
    JNIEnv*,
    jobject
) {
    static hzzs::analysis::NativeAnalysisEngine engine{};
    engine.Reset();
    __android_log_print(ANDROID_LOG_DEBUG, kLogTag, "[JNI] engine reset.");
}
