#include "HzzsVisionCore.h"
#include <jni.h>
#include <array>

extern "C" JNIEXPORT jintArray JNICALL
Java_top_azek431_hzzs_runtime_vision_HzzsVisionBridge_nativeAnalyze(
    JNIEnv* env, jobject, jintArray pixels, jint width, jint height, jint coarseStep, jint spikeStep) {
    if (!pixels || width <= 0 || height <= 0 || env->GetArrayLength(pixels) < width * height) return nullptr;
    jint* raw = static_cast<jint*>(env->GetPrimitiveArrayCritical(pixels, nullptr));
    if (!raw) return nullptr;
    const hzzs::vision2::FrameView frame{
        reinterpret_cast<const std::uint32_t*>(raw), width, height, width
    };
    const auto result = hzzs::vision2::analyze(frame, coarseStep, spikeStep);
    std::array<std::int32_t, hzzs::vision2::kResultInts> packed{};
    hzzs::vision2::pack(result, packed.data(), static_cast<int>(packed.size()));
    env->ReleasePrimitiveArrayCritical(pixels, raw, JNI_ABORT);
    jintArray output = env->NewIntArray(static_cast<jsize>(packed.size()));
    if (!output) return nullptr;
    env->SetIntArrayRegion(output, 0, static_cast<jsize>(packed.size()), reinterpret_cast<const jint*>(packed.data()));
    return output;
}
