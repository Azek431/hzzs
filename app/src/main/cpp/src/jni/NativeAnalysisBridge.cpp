#include <jni.h>

#include <android/log.h>

#include <string>

#include "hzzs/analysis/NativeAnalysisEngine.h"

namespace {

constexpr const char* kLogTag = "HZZS-Native";

jstring ToJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

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

extern "C"
JNIEXPORT jstring JNICALL
Java_top_azek431_hzzs_NativeAnalysisBridge_nativeRunSelfCheck(
    JNIEnv* env,
    jobject
) {
    hzzs::analysis::NativeAnalysisEngine engine{};

    engine.Analyze(CreateGroundFrame(16));
    const hzzs::analysis::AnalysisResult result = engine.Analyze(
        CreateGroundFrame(32)
    );

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
