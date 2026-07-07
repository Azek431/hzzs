#include <jni.h>

#include <android/log.h>

#include <string>

#include "hzzs/analysis/NativeAnalysisEngine.h"

namespace {

constexpr const char* kLogTag = "HZZS-Native";

jstring ToJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
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
        "RunnerStateMachine + ActionPromptEngine";

    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", message.c_str());
    return ToJString(env, message);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_top_azek431_hzzs_NativeAnalysisBridge_nativeRunSelfCheck(
    JNIEnv* env,
    jobject
) {
    hzzs::analysis::NativeAnalysisEngine engine{};

    hzzs::analysis::FrameDetections first_frame{};
    first_frame.timestamp_ms = 16;
    first_frame.player_bounds = hzzs::analysis::RectF{
        0.15F,
        0.68F,
        0.24F,
        0.86F
    };
    first_frame.player_confidence = 0.95F;
    first_frame.ground_hazard_eta_ms = 420.0F;
    first_frame.ground_hazard_confidence = 0.90F;

    hzzs::analysis::FrameDetections second_frame = first_frame;
    second_frame.timestamp_ms = 32;
    second_frame.ground_hazard_eta_ms = 390.0F;

    engine.Analyze(first_frame);
    const hzzs::analysis::AnalysisResult result = engine.Analyze(second_frame);

    const bool passed = (
        result.pose == hzzs::analysis::RunnerPose::kRun &&
        result.suggested_action == hzzs::analysis::PromptAction::kJump
    );

    const std::string message = passed
        ? "PASS: native runner state and two-frame jump prompt check."
        : "FAIL: native runner state self-check.";

    __android_log_print(
        passed ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
        kLogTag,
        "%s",
        message.c_str()
    );

    return ToJString(env, message);
}
