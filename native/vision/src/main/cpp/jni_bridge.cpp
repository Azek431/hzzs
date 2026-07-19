#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <new>
#include <string>
#include <vector>

#include "vision_engine.h"

namespace {
constexpr std::size_t kMaximumDetections = 128;
constexpr int kMaximumDimension = 8192;

bool clear_if_exception(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionClear();
    return true;
}

jobject make_result(JNIEnv* env, const hzzs::Result& native_result) {
    jclass detection_class = env->FindClass("top/azek431/hzzs/nativevision/NativeVision$Detection");
    if (!detection_class || clear_if_exception(env)) return nullptr;
    jmethodID detection_ctor = env->GetMethodID(detection_class, "<init>", "(IIFFFFFZZI)V");
    if (!detection_ctor || clear_if_exception(env)) {
        env->DeleteLocalRef(detection_class);
        return nullptr;
    }

    const auto count = static_cast<jsize>(std::min(native_result.detections.size(), kMaximumDetections));
    jobjectArray detections = env->NewObjectArray(count, detection_class, nullptr);
    if (!detections || clear_if_exception(env)) {
        env->DeleteLocalRef(detection_class);
        return nullptr;
    }
    for (jsize index = 0; index < count; ++index) {
        const auto& d = native_result.detections[static_cast<std::size_t>(index)];
        jobject object = env->NewObject(
            detection_class,
            detection_ctor,
            d.track_hint,
            static_cast<jint>(d.kind),
            d.bounds.left,
            d.bounds.top,
            d.bounds.right,
            d.bounds.bottom,
            d.confidence,
            static_cast<jboolean>(d.actionable),
            static_cast<jboolean>(d.diagnostic_only),
            static_cast<jint>(d.avoidance));
        if (!object || clear_if_exception(env)) continue;
        env->SetObjectArrayElement(detections, index, object);
        env->DeleteLocalRef(object);
        if (clear_if_exception(env)) break;
    }

    jclass result_class = env->FindClass("top/azek431/hzzs/nativevision/NativeVision$Result");
    if (!result_class || clear_if_exception(env)) {
        env->DeleteLocalRef(detections);
        env->DeleteLocalRef(detection_class);
        return nullptr;
    }
    jmethodID result_ctor = env->GetMethodID(
        result_class,
        "<init>",
        "(F[Ltop/azek431/hzzs/nativevision/NativeVision$Detection;Ljava/lang/String;)V");
    if (!result_ctor || clear_if_exception(env)) {
        env->DeleteLocalRef(result_class);
        env->DeleteLocalRef(detections);
        env->DeleteLocalRef(detection_class);
        return nullptr;
    }
    jstring error = env->NewStringUTF(native_result.error.c_str());
    if (!error || clear_if_exception(env)) {
        env->DeleteLocalRef(result_class);
        env->DeleteLocalRef(detections);
        env->DeleteLocalRef(detection_class);
        return nullptr;
    }
    jobject output = env->NewObject(
        result_class,
        result_ctor,
        native_result.scene_confidence,
        detections,
        error);
    clear_if_exception(env);
    env->DeleteLocalRef(error);
    env->DeleteLocalRef(result_class);
    env->DeleteLocalRef(detections);
    env->DeleteLocalRef(detection_class);
    return output;
}

jobject error_result(JNIEnv* env, const char* message) {
    hzzs::Result result;
    result.error = message;
    return make_result(env, result);
}

bool finite_viewport(float left, float top, float right, float bottom) {
    return std::isfinite(left) && std::isfinite(top) && std::isfinite(right) &&
           std::isfinite(bottom) && left >= 0.0f && top >= 0.0f && right <= 1.0f &&
           bottom <= 1.0f && right - left >= 0.01f && bottom - top >= 0.01f;
}
}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_top_azek431_hzzs_nativevision_NativeVision_analyze(
    JNIEnv* env,
    jobject,
    jint scene,
    jintArray pixels,
    jint width,
    jint height,
    jint work_width,
    jfloat viewport_left,
    jfloat viewport_top,
    jfloat viewport_right,
    jfloat viewport_bottom) {
    if (scene < 0 || scene > 1) return error_result(env, "invalid scene");
    if (!pixels || width <= 0 || height <= 0 || width > kMaximumDimension || height > kMaximumDimension) {
        return error_result(env, "invalid frame dimensions");
    }
    if (work_width < 160 || work_width > 720) return error_result(env, "invalid work width");
    if (!finite_viewport(viewport_left, viewport_top, viewport_right, viewport_bottom)) {
        return error_result(env, "invalid viewport");
    }

    const int64_t expected = static_cast<int64_t>(width) * static_cast<int64_t>(height);
    if (expected <= 0 || expected > std::numeric_limits<jsize>::max()) {
        return error_result(env, "frame size overflow");
    }
    const jsize length = env->GetArrayLength(pixels);
    if (clear_if_exception(env) || length != static_cast<jsize>(expected)) {
        return error_result(env, "pixel array length mismatch");
    }

    const int crop_left = std::clamp(static_cast<int>(std::floor(viewport_left * width)), 0, width - 1);
    const int crop_top = std::clamp(static_cast<int>(std::floor(viewport_top * height)), 0, height - 1);
    const int crop_right = std::clamp(static_cast<int>(std::ceil(viewport_right * width)), crop_left + 1, width);
    const int crop_bottom = std::clamp(static_cast<int>(std::ceil(viewport_bottom * height)), crop_top + 1, height);
    const int crop_width = crop_right - crop_left;
    const int crop_height = crop_bottom - crop_top;
    const int64_t crop_size = static_cast<int64_t>(crop_width) * static_cast<int64_t>(crop_height);
    if (crop_size <= 0 || crop_size > std::numeric_limits<jsize>::max()) {
        return error_result(env, "viewport size overflow");
    }

    try {
        std::vector<uint32_t> buffer(static_cast<std::size_t>(crop_size));
        for (int row = 0; row < crop_height; ++row) {
            const int64_t source_offset = static_cast<int64_t>(crop_top + row) * width + crop_left;
            const int64_t target_offset = static_cast<int64_t>(row) * crop_width;
            env->GetIntArrayRegion(
                pixels,
                static_cast<jsize>(source_offset),
                static_cast<jsize>(crop_width),
                reinterpret_cast<jint*>(buffer.data() + target_offset));
            if (clear_if_exception(env)) return error_result(env, "failed to copy viewport row");
        }
        const hzzs::Result result = hzzs::analyze(
            scene,
            {buffer.data(), crop_width, crop_height},
            work_width);
        return make_result(env, result);
    } catch (const std::bad_alloc&) {
        return error_result(env, "native allocation failed");
    } catch (...) {
        return error_result(env, "native analysis failed");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_azek431_hzzs_nativevision_NativeVision_reset(JNIEnv*, jobject) {
    hzzs::reset();
}
