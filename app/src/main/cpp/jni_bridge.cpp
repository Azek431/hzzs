/**
 * JNI 边界：Kotlin NativeVision <-> C++ vision_engine / algorithm_runtime。
 *
 * 职责：
 * - 校验帧尺寸与数组长度，借用 Java int[]（GetPrimitiveArrayCritical + JNI_ABORT）
 * - 视口裁剪后调用 analyze；结果编码为 Java Detection/Result
 * - configureAlgorithm 与 analyze 共用 g_analysis_mutex，禁止半热切换
 *
 * 不变量：Native 不持有 Java 数组地址跨调用；异常一律 clear 并返回失败对象。
 */
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include "algorithm_runtime.h"
#include "vision_engine.h"

namespace {
constexpr std::size_t kMaximumDetections = 128;
constexpr int kMaximumDimension = 4096;
constexpr int64_t kMaximumPixels = 8'388'608;

/** 与 analyze 串行：configure/reset 不得与 analyze 半热交错。 */
std::mutex g_analysis_mutex;

bool clear_if_exception(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionClear();
    return true;
}

/**
 * 关键区借用 Java int[] 像素；析构时 JNI_ABORT 释放，不回写。
 * 作用域必须短于 JNI 调用，禁止跨线程持有。
 */
class CriticalIntArray final {
public:
    CriticalIntArray(JNIEnv* env, jintArray array)
        : env_(env), array_(array), data_(static_cast<jint*>(
              env->GetPrimitiveArrayCritical(array, nullptr))) {}

    ~CriticalIntArray() {
        if (data_ != nullptr) {
            env_->ReleasePrimitiveArrayCritical(array_, data_, JNI_ABORT);
        }
    }

    CriticalIntArray(const CriticalIntArray&) = delete;
    CriticalIntArray& operator=(const CriticalIntArray&) = delete;

    const uint32_t* pixels() const {
        static_assert(sizeof(jint) == sizeof(uint32_t));
        return reinterpret_cast<const uint32_t*>(data_);
    }

    bool valid() const { return data_ != nullptr; }

private:
    JNIEnv* env_;
    jintArray array_;
    jint* data_;
};

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

jobject make_config_result(JNIEnv* env, const hzzs::AlgorithmConfigResult& config) {
    jclass cls = env->FindClass("top/azek431/hzzs/nativevision/NativeVision$ConfigResult");
    if (!cls || clear_if_exception(env)) return nullptr;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ZJZLjava/lang/String;)V");
    if (!ctor || clear_if_exception(env)) {
        env->DeleteLocalRef(cls);
        return nullptr;
    }
    jstring error = env->NewStringUTF(config.error.c_str());
    if (!error || clear_if_exception(env)) {
        env->DeleteLocalRef(cls);
        return nullptr;
    }
    jobject out = env->NewObject(
        cls,
        ctor,
        static_cast<jboolean>(config.ok),
        static_cast<jlong>(config.generation),
        static_cast<jboolean>(config.using_builtin_fallback),
        error);
    clear_if_exception(env);
    env->DeleteLocalRef(error);
    env->DeleteLocalRef(cls);
    return out;
}

bool read_float_field(JNIEnv* env, jobject obj, jclass cls, const char* name, float* out) {
    jfieldID field = env->GetFieldID(cls, name, "F");
    if (!field || clear_if_exception(env)) return false;
    *out = env->GetFloatField(obj, field);
    return !clear_if_exception(env);
}

bool read_int_field(JNIEnv* env, jobject obj, jclass cls, const char* name, int32_t* out) {
    jfieldID field = env->GetFieldID(cls, name, "I");
    if (!field || clear_if_exception(env)) return false;
    *out = env->GetIntField(obj, field);
    return !clear_if_exception(env);
}

bool read_bool_field(JNIEnv* env, jobject obj, jclass cls, const char* name, int32_t* out) {
    jfieldID field = env->GetFieldID(cls, name, "Z");
    if (!field || clear_if_exception(env)) return false;
    *out = env->GetBooleanField(obj, field) == JNI_TRUE ? 1 : 0;
    return !clear_if_exception(env);
}

bool read_string_field(JNIEnv* env, jobject obj, jclass cls, const char* name, char* dest, std::size_t dest_size) {
    jfieldID field = env->GetFieldID(cls, name, "Ljava/lang/String;");
    if (!field || clear_if_exception(env)) return false;
    auto jstr = static_cast<jstring>(env->GetObjectField(obj, field));
    if (clear_if_exception(env)) return false;
    if (!jstr) {
        dest[0] = '\0';
        return true;
    }
    const char* utf = env->GetStringUTFChars(jstr, nullptr);
    if (!utf || clear_if_exception(env)) {
        env->DeleteLocalRef(jstr);
        return false;
    }
    std::strncpy(dest, utf, dest_size - 1);
    dest[dest_size - 1] = '\0';
    env->ReleaseStringUTFChars(jstr, utf);
    env->DeleteLocalRef(jstr);
    return true;
}

bool read_color_thresholds(
    JNIEnv* env,
    jobject colors_obj,
    hzzs::SceneColorThresholdsNative* out) {
    if (!colors_obj) return false;
    jclass cls = env->GetObjectClass(colors_obj);
    if (!cls || clear_if_exception(env)) return false;
    bool ok = true;
    ok = ok && read_int_field(env, colors_obj, cls, "bottleGreenMin", &out->bottle_green_min);
    ok = ok && read_float_field(env, colors_obj, cls, "bottleGreenOverRed", &out->bottle_green_over_red);
    ok = ok && read_float_field(env, colors_obj, cls, "bottleGreenOverBlue", &out->bottle_green_over_blue);
    ok = ok && read_int_field(env, colors_obj, cls, "bottleRedMax", &out->bottle_red_max);
    ok = ok && read_int_field(env, colors_obj, cls, "cakeRedMin", &out->cake_red_min);
    ok = ok && read_int_field(env, colors_obj, cls, "cakeGreenMin", &out->cake_green_min);
    ok = ok && read_int_field(env, colors_obj, cls, "cakeBlueMax", &out->cake_blue_max);
    ok = ok && read_int_field(env, colors_obj, cls, "spikeRedMin", &out->spike_red_min);
    ok = ok && read_int_field(env, colors_obj, cls, "spikeBlueMin", &out->spike_blue_min);
    ok = ok && read_float_field(env, colors_obj, cls, "spikeRedOverGreen", &out->spike_red_over_green);
    ok = ok && read_int_field(env, colors_obj, cls, "bambooGreenMin", &out->bamboo_green_min);
    ok = ok && read_float_field(env, colors_obj, cls, "bambooGreenOverRed", &out->bamboo_green_over_red);
    ok = ok && read_float_field(env, colors_obj, cls, "bambooGreenOverBlue", &out->bamboo_green_over_blue);
    ok = ok && read_int_field(env, colors_obj, cls, "bambooBlueMax", &out->bamboo_blue_max);
    ok = ok && read_int_field(env, colors_obj, cls, "brushDarkMax", &out->brush_dark_max);
    ok = ok && read_int_field(env, colors_obj, cls, "statueChromaMax", &out->statue_chroma_max);
    env->DeleteLocalRef(cls);
    return ok;
}

bool read_scene_params(JNIEnv* env, jobject params_obj, hzzs::SceneAlgorithmParamsNative* out) {
    if (!params_obj) return false;
    jclass cls = env->GetObjectClass(params_obj);
    if (!cls || clear_if_exception(env)) return false;
    bool ok = true;
    ok = ok && read_float_field(env, params_obj, cls, "sceneConfidenceFloor", &out->scene_confidence_floor);
    ok = ok && read_float_field(env, params_obj, cls, "playerConfidenceFloor", &out->player_confidence_floor);
    ok = ok && read_float_field(env, params_obj, cls, "fixedPlayerTop", &out->fixed_player_top);
    ok = ok && read_float_field(env, params_obj, cls, "fixedPlayerBottom", &out->fixed_player_bottom);
    ok = ok && read_int_field(env, params_obj, cls, "fixedPlayerWidthDivisor", &out->fixed_player_width_divisor);
    ok = ok && read_float_field(env, params_obj, cls, "fallbackSceneConfidenceMax", &out->fallback_scene_confidence_max);
    ok = ok && read_int_field(env, params_obj, cls, "fallbackMaxDetections", &out->fallback_max_detections);
    ok = ok && read_float_field(env, params_obj, cls, "groundSearchTop", &out->ground_search_top);
    ok = ok && read_float_field(env, params_obj, cls, "groundSearchBottom", &out->ground_search_bottom);
    ok = ok && read_float_field(env, params_obj, cls, "groundConfidenceMin", &out->ground_confidence_min);
    ok = ok && read_float_field(env, params_obj, cls, "bottleWidthMin", &out->bottle_width_min);
    ok = ok && read_float_field(env, params_obj, cls, "bottleWidthMax", &out->bottle_width_max);
    ok = ok && read_float_field(env, params_obj, cls, "bottleHeightMin", &out->bottle_height_min);
    ok = ok && read_float_field(env, params_obj, cls, "bottleHeightMax", &out->bottle_height_max);
    ok = ok && read_float_field(env, params_obj, cls, "cakeWidthMin", &out->cake_width_min);
    ok = ok && read_float_field(env, params_obj, cls, "cakeWidthMax", &out->cake_width_max);
    ok = ok && read_float_field(env, params_obj, cls, "cakeHeightMin", &out->cake_height_min);
    ok = ok && read_float_field(env, params_obj, cls, "cakeWideWidthRatio", &out->cake_wide_width_ratio);
    ok = ok && read_float_field(env, params_obj, cls, "statueWidthMin", &out->statue_width_min);
    ok = ok && read_float_field(env, params_obj, cls, "statueWidthMax", &out->statue_width_max);
    ok = ok && read_float_field(env, params_obj, cls, "statueHeightMin", &out->statue_height_min);
    ok = ok && read_float_field(env, params_obj, cls, "statueHeightMax", &out->statue_height_max);
    ok = ok && read_float_field(env, params_obj, cls, "gapWidthMin", &out->gap_width_min);
    ok = ok && read_float_field(env, params_obj, cls, "gapWidthMax", &out->gap_width_max);
    ok = ok && read_float_field(env, params_obj, cls, "gapHeightMin", &out->gap_height_min);
    ok = ok && read_float_field(env, params_obj, cls, "gapWideWidthRatio", &out->gap_wide_width_ratio);
    ok = ok && read_float_field(env, params_obj, cls, "brushWidthMin", &out->brush_width_min);
    ok = ok && read_float_field(env, params_obj, cls, "brushWidthMax", &out->brush_width_max);
    ok = ok && read_float_field(env, params_obj, cls, "brushHeightMin", &out->brush_height_min);
    ok = ok && read_float_field(env, params_obj, cls, "brushHeightMax", &out->brush_height_max);
    ok = ok && read_float_field(env, params_obj, cls, "spikeWidthMin", &out->spike_width_min);
    ok = ok && read_float_field(env, params_obj, cls, "spikeWidthMax", &out->spike_width_max);
    ok = ok && read_float_field(env, params_obj, cls, "spikeHeightMin", &out->spike_height_min);
    ok = ok && read_float_field(env, params_obj, cls, "spikeHeightMax", &out->spike_height_max);

    jfieldID colors_field = env->GetFieldID(cls, "colors", "Ltop/azek431/hzzs/domain/vision/SceneColorThresholds;");
    if (!colors_field || clear_if_exception(env)) {
        env->DeleteLocalRef(cls);
        return false;
    }
    jobject colors_obj = env->GetObjectField(params_obj, colors_field);
    if (clear_if_exception(env) || !colors_obj) {
        env->DeleteLocalRef(cls);
        return false;
    }
    ok = ok && read_color_thresholds(env, colors_obj, &out->colors);
    env->DeleteLocalRef(colors_obj);
    env->DeleteLocalRef(cls);
    return ok;
}

bool map_profile(JNIEnv* env, jobject profile_obj, hzzs::AlgorithmRuntimeProfileNative* out) {
    if (!profile_obj) return false;
    jclass cls = env->GetObjectClass(profile_obj);
    if (!cls || clear_if_exception(env)) return false;

    if (!read_string_field(env, profile_obj, cls, "algorithmId", out->algorithm_id, sizeof(out->algorithm_id))) {
        env->DeleteLocalRef(cls);
        return false;
    }
    if (!read_string_field(env, profile_obj, cls, "version", out->version, sizeof(out->version))) {
        env->DeleteLocalRef(cls);
        return false;
    }
    if (!read_int_field(env, profile_obj, cls, "schemaVersion", &out->schema_version)) {
        env->DeleteLocalRef(cls);
        return false;
    }
    if (!read_bool_field(env, profile_obj, cls, "isBuiltin", &out->is_builtin)) {
        env->DeleteLocalRef(cls);
        return false;
    }
    out->generation = 1;

    // scenes: Map<SceneId, SceneAlgorithmParams>
    jfieldID scenes_field = env->GetFieldID(cls, "scenes", "Ljava/util/Map;");
    if (!scenes_field || clear_if_exception(env)) {
        env->DeleteLocalRef(cls);
        return false;
    }
    jobject scenes_map = env->GetObjectField(profile_obj, scenes_field);
    if (clear_if_exception(env) || !scenes_map) {
        env->DeleteLocalRef(cls);
        return false;
    }
    jclass map_cls = env->GetObjectClass(scenes_map);
    jmethodID get_mid = env->GetMethodID(map_cls, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (!get_mid || clear_if_exception(env)) {
        env->DeleteLocalRef(map_cls);
        env->DeleteLocalRef(scenes_map);
        env->DeleteLocalRef(cls);
        return false;
    }

    jclass scene_id_cls = env->FindClass("top/azek431/hzzs/core/model/SceneId");
    if (!scene_id_cls || clear_if_exception(env)) {
        env->DeleteLocalRef(map_cls);
        env->DeleteLocalRef(scenes_map);
        env->DeleteLocalRef(cls);
        return false;
    }
    jmethodID values_mid = env->GetStaticMethodID(scene_id_cls, "values", "()[Ltop/azek431/hzzs/core/model/SceneId;");
    if (!values_mid || clear_if_exception(env)) {
        env->DeleteLocalRef(scene_id_cls);
        env->DeleteLocalRef(map_cls);
        env->DeleteLocalRef(scenes_map);
        env->DeleteLocalRef(cls);
        return false;
    }
    auto values = static_cast<jobjectArray>(env->CallStaticObjectMethod(scene_id_cls, values_mid));
    if (!values || clear_if_exception(env)) {
        env->DeleteLocalRef(scene_id_cls);
        env->DeleteLocalRef(map_cls);
        env->DeleteLocalRef(scenes_map);
        env->DeleteLocalRef(cls);
        return false;
    }
    const jsize scene_count = env->GetArrayLength(values);
    if (scene_count != hzzs::kSceneCount) {
        env->DeleteLocalRef(values);
        env->DeleteLocalRef(scene_id_cls);
        env->DeleteLocalRef(map_cls);
        env->DeleteLocalRef(scenes_map);
        env->DeleteLocalRef(cls);
        return false;
    }
    for (jsize i = 0; i < scene_count; ++i) {
        jobject scene_id = env->GetObjectArrayElement(values, i);
        if (!scene_id || clear_if_exception(env)) {
            env->DeleteLocalRef(values);
            env->DeleteLocalRef(scene_id_cls);
            env->DeleteLocalRef(map_cls);
            env->DeleteLocalRef(scenes_map);
            env->DeleteLocalRef(cls);
            return false;
        }
        jobject params_obj = env->CallObjectMethod(scenes_map, get_mid, scene_id);
        env->DeleteLocalRef(scene_id);
        if (!params_obj || clear_if_exception(env)) {
            env->DeleteLocalRef(values);
            env->DeleteLocalRef(scene_id_cls);
            env->DeleteLocalRef(map_cls);
            env->DeleteLocalRef(scenes_map);
            env->DeleteLocalRef(cls);
            return false;
        }
        if (!read_scene_params(env, params_obj, &out->scenes[i])) {
            env->DeleteLocalRef(params_obj);
            env->DeleteLocalRef(values);
            env->DeleteLocalRef(scene_id_cls);
            env->DeleteLocalRef(map_cls);
            env->DeleteLocalRef(scenes_map);
            env->DeleteLocalRef(cls);
            return false;
        }
        env->DeleteLocalRef(params_obj);
    }
    env->DeleteLocalRef(values);
    env->DeleteLocalRef(scene_id_cls);
    env->DeleteLocalRef(map_cls);
    env->DeleteLocalRef(scenes_map);
    env->DeleteLocalRef(cls);
    return true;
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
    jint enabled_kind_mask,
    jboolean detect_player,
    jfloat fixed_player_x_ratio,
    jfloat viewport_left,
    jfloat viewport_top,
    jfloat viewport_right,
    jfloat viewport_bottom) {
    std::lock_guard<std::mutex> analysis_lock(g_analysis_mutex);
    if (scene < 0 || scene > 1) return error_result(env, "invalid scene");
    if (!pixels || width <= 0 || height <= 0 || width > kMaximumDimension || height > kMaximumDimension) {
        return error_result(env, "invalid frame dimensions");
    }
    if (work_width < 160 || work_width > 960) return error_result(env, "invalid work width");
    if (!finite_viewport(viewport_left, viewport_top, viewport_right, viewport_bottom)) {
        return error_result(env, "invalid viewport");
    }

    const int64_t expected = static_cast<int64_t>(width) * static_cast<int64_t>(height);
    if (expected <= 0 || expected > kMaximumPixels || expected > std::numeric_limits<jsize>::max()) {
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
        hzzs::Result result;
        {
            CriticalIntArray pinned(env, pixels);
            if (!pinned.valid() || clear_if_exception(env)) {
                return error_result(env, "failed to access pixel array");
            }

            const bool full_viewport = crop_left == 0 && crop_top == 0 &&
                                       crop_width == width && crop_height == height;
            if (full_viewport) {
                result = hzzs::analyze(
                    scene,
                    {pinned.pixels(), width, height},
                    work_width,
                    enabled_kind_mask,
                    detect_player == JNI_TRUE,
                    fixed_player_x_ratio);
            } else {
                std::vector<uint32_t> buffer(static_cast<std::size_t>(crop_size));
                for (int row = 0; row < crop_height; ++row) {
                    const int64_t source_offset =
                        static_cast<int64_t>(crop_top + row) * width + crop_left;
                    const int64_t target_offset = static_cast<int64_t>(row) * crop_width;
                    std::copy_n(
                        pinned.pixels() + source_offset,
                        crop_width,
                        buffer.data() + target_offset);
                }
                result = hzzs::analyze(
                    scene,
                    {buffer.data(), crop_width, crop_height},
                    work_width,
                    enabled_kind_mask,
                    detect_player == JNI_TRUE,
                    fixed_player_x_ratio);
            }
        }
        return make_result(env, result);
    } catch (const std::bad_alloc&) {
        return error_result(env, "native allocation failed");
    } catch (...) {
        return error_result(env, "native analysis failed");
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_top_azek431_hzzs_nativevision_NativeVision_configureAlgorithm(
    JNIEnv* env,
    jobject,
    jobject profile_obj) {
    std::lock_guard<std::mutex> analysis_lock(g_analysis_mutex);
    hzzs::AlgorithmConfigResult result;
    if (!profile_obj) {
        result.ok = false;
        result.error = "null profile";
        result.generation = hzzs::AlgorithmRuntime::instance().generation();
        result.using_builtin_fallback = true;
        return make_config_result(env, result);
    }
    hzzs::AlgorithmRuntimeProfileNative mapped = hzzs::make_builtin_profile(1);
    if (!map_profile(env, profile_obj, &mapped)) {
        result.ok = false;
        result.error = "failed to map algorithm profile";
        result.generation = hzzs::AlgorithmRuntime::instance().generation();
        result.using_builtin_fallback = true;
        return make_config_result(env, result);
    }
    result = hzzs::AlgorithmRuntime::instance().configure(mapped);
    return make_config_result(env, result);
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_azek431_hzzs_nativevision_NativeVision_activeAlgorithmGeneration(
    JNIEnv*,
    jobject) {
    return static_cast<jlong>(hzzs::AlgorithmRuntime::instance().generation());
}

extern "C" JNIEXPORT void JNICALL
Java_top_azek431_hzzs_nativevision_NativeVision_reset(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> analysis_lock(g_analysis_mutex);
    hzzs::reset();
}
