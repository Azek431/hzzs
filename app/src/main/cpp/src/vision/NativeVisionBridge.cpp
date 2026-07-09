// 火崽崽助手（HZZS）视觉识别桥接层 — JNI 实现。
//
// 职责：
// - 独立于主 NativeAnalysisBridge 的 JNI 导出层
// - 暴露绿瓶单行扫描检测器的 JNI 接口
// - 将 Kotlin 端的像素数组 + 玩家坐标传入 C++ 检测器
// - 返回 JSON 格式的检测结果
//
// 导出 JNI 函数：
// - nativeVisionScanGreenBottle：执行绿瓶扫描，返回 JSON 结果
//
// 关键设计决策：
// - 使用独立的 GreenBottleLineDetector 实例，不污染主分析引擎
// - 像素数组通过 JNI 直接传入，避免不必要的拷贝
// - JSON 序列化手动拼接，不依赖第三方库

#include <jni.h>
#include <android/log.h>
#include <sstream>
#include <string>

#include "hzzs/vision/GreenBottleLineDetector.h"

namespace {

/** 日志标签，用于 Android logcat 中过滤视觉模块日志 */
constexpr const char* kLogTag = "HZZS-Vision";

/**
 * 将 C++ std::string 转换为 JNI jstring。
 */
jstring ToJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

/**
 * JNI 导出方法：绿瓶单行扫描检测。
 *
 * 由 Kotlin 端 VisionAnalysisBridge.scanGreenBottle() 调用。
 *
 * 参数说明：
 * - pixels：ARGB 像素数组（int[]），长度为 width * height
 * - width：屏幕宽度（像素）
 * - height：屏幕高度（像素）
 * - player_left/top/right/bottom：玩家矩形归一化坐标
 *
 * @param env JNI 环境指针
 * @param obj JNI 对象（此方法不需要，保留占位）
 * @param pixels_j Java int[] 像素数组
 * @param width 屏幕宽度
 * @param height 屏幕高度
 * @param player_left 玩家左边界
 * @param player_top 玩家上边界
 * @param player_right 玩家右边界
 * @param player_bottom 玩家下边界
 * @return JSON 格式的 GreenBottleResult 字符串
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_top_azek431_hzzs_data_vision_VisionAnalysisBridge_nativeVisionScanGreenBottle(
    JNIEnv* env,
    jobject,
    jintArray pixels_j,
    jint width,
    jint height,
    jfloat player_left,
    jfloat player_top,
    jfloat player_right,
    jfloat player_bottom
) {
    hzzs::vision::GreenBottleResult result{};

    // 参数校验
    if (pixels_j == nullptr) {
        std::string error = "{\"found\":false,\"error\":\"pixels array is null\"}";
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "[JNI] scanGreenBottle: pixels array is null"
        );
        return ToJString(env, error);
    }

    if (width <= 0 || height <= 0) {
        std::string error = "{\"found\":false,\"error\":\"invalid dimensions\"}";
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "[JNI] scanGreenBottle: invalid dimensions %dx%d",
            width,
            height
        );
        return ToJString(env, error);
    }

    if (player_right <= player_left || player_bottom <= player_top) {
        std::string error = "{\"found\":false,\"error\":\"invalid player bounds\"}";
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "[JNI] scanGreenBottle: invalid player bounds [%f,%f,%f,%f]",
            player_left, player_top, player_right, player_bottom
        );
        return ToJString(env, error);
    }

    // 锁定 Java int[] 数组
    std::int32_t* pixels = env->GetIntArrayElements(pixels_j, nullptr);
    if (pixels == nullptr) {
        std::string error = "{\"found\":false,\"error\":\"failed to lock pixels array\"}";
        return ToJString(env, error);
    }

    // 执行绿瓶扫描
    static hzzs::vision::GreenBottleLineDetector detector{};
    result = detector.Scan(
        pixels, width, height,
        player_left, player_top, player_right, player_bottom
    );

    // 解锁数组（允许 JVM 回收或重用）
    env->ReleaseIntArrayElements(pixels_j, pixels, JNI_ABORT);

    // 序列化结果为 JSON
    std::ostringstream json;
    json << "{\"found\":" << (result.found ? "true" : "false");

    if (result.found) {
        json << ",\"scanY\":" << result.scan_y
             << ",\"leftX\":" << result.left_x
             << ",\"rightX\":" << result.right_x
             << ",\"centerX\":" << result.center_x
             << ",\"edgeGapPx\":" << result.edge_gap_px
             << ",\"centerDistancePx\":" << result.center_distance_px
             << ",\"leftRatio\":" << result.left_ratio
             << ",\"rightRatio\":" << result.right_ratio
             << ",\"centerXRatio\":" << result.center_x_ratio
             << ",\"edgeGapRatio\":" << result.edge_gap_ratio
             << ",\"costMs\":" << result.cost_ms
             << ",\"confidence\":" << result.confidence
             << ",\"rawSegmentCount\":" << result.raw_segment_count
             << ",\"mergedSegmentCount\":" << result.merged_segment_count;
    }

    json << "}";

    std::string output = json.str();

    __android_log_print(
        ANDROID_LOG_DEBUG,
        kLogTag,
        "[JNI] greenBottle scan -> %s",
        output.c_str()
    );

    return ToJString(env, output);
}
