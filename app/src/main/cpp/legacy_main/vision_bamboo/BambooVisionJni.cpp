#include "BambooVisionCore.h"
#include <jni.h>
#include <array>

extern "C" JNIEXPORT jintArray JNICALL
Java_top_azek431_hzzs_runtime_vision_HzzsVisionBridge_nativeAnalyzeBamboo(
    JNIEnv* env,
    jobject,
    jintArray pixels,
    jint width,
    jint height) {
    if (pixels == nullptr || width <= 0 || height <= 0) return nullptr;
    const jsize length = env->GetArrayLength(pixels);
    if (length < width * height) return nullptr;

    jint* rawPixels = env->GetIntArrayElements(pixels, nullptr);
    if (rawPixels == nullptr) return nullptr;

    std::array<std::int32_t, hzzs::vision_bamboo::kResultInts> packed{};
    const int written = hzzs_bamboo_analyze_packed(
        reinterpret_cast<const std::uint32_t*>(rawPixels),
        width,
        height,
        width,
        packed.data(),
        static_cast<int>(packed.size()));
    env->ReleaseIntArrayElements(pixels, rawPixels, JNI_ABORT);
    if (written != hzzs::vision_bamboo::kResultInts) return nullptr;

    jintArray result = env->NewIntArray(written);
    if (result == nullptr) return nullptr;
    env->SetIntArrayRegion(result, 0, written, reinterpret_cast<const jint*>(packed.data()));
    return result;
}
