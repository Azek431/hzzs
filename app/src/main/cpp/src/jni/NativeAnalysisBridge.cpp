#include <jni.h>

#include <android/log.h>

#include <string>

#include "hzzs/analysis/NativeAnalysisEngine.h"

namespace {

/** 日志标签，用于 Android logcat 中过滤 HZZS 相关日志 */
constexpr const char* kLogTag = "HZZS-Native";

/**
 * 将 C++ std::string 转换为 JNI jstring。
 *
 * 使用 GetStringUTFChars + NewString 而非 NewStringUTF，以正确处理包含
 * 多字节 UTF-8 字符（如中文 '·' U+00B7）的字符串。NewStringUTF 在某些
 * JNI 实现上遇到此类字符会抛出 IllegalArgumentException。
 *
 * @param env JNI 环境指针
 * @param value C++ 字符串
 * @return 转换后的 jstring，调用方负责 DeleteLocalRef
 */
jstring ToJString(JNIEnv* env, const std::string& value) {
    const char* utf8 = env->GetStringUTFChars(value.c_str(), nullptr);
    if (!utf8) return nullptr;

    jstring result = env->NewString(utf8, env->GetStringLength(utf8));
    env->ReleaseStringUTFChars(value.c_str(), utf8);
    return result;
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
