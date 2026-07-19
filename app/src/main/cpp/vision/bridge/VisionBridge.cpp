// 火崽崽助手（HZZS）视觉识别 — JNI 桥接层。
//
// 将 C++ 视觉算法（绿瓶检测 + 坑位检测 + 绘制 + 日志）暴露给 Kotlin。
// 所有函数都是 JNI 导出，命名遵循 Java_package_Class_method 规范。

#include <jni.h>
#include <android/log.h>
#include <cstring>

#include "../algorithm/VisionTypes.h"
#include "../algorithm/GreenBottleDetector.h"
#include "../algorithm/PitDetector.h"
#include "../renderer/DebugRenderer.h"
#include "../logger/LogManager.h"

#define LOG_TAG "HZZS-Vision"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================
// 辅助函数
// ============================================================

/** 将 C++ 结构体序列化为 Java byte[] */
static jbyteArray structToByteArray(JNIEnv* env, const void* data, size_t size) {
    if (data == nullptr || size == 0) {
        return env->NewByteArray(0);
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(size),
                            reinterpret_cast<const jbyte*>(data));
    return result;
}

/** 从 byte[] 反序列化为 C++ 结构体 */
static void byteArrayToStruct(JNIEnv* env, jbyteArray data, void* out, size_t size) {
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    std::memcpy(out, bytes, size);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

// ============================================================
// JNI 导出函数
// ============================================================

/**
 * 绿瓶检测 JNI 入口。
 *
 * @param rgbPixels RGB 像素数组（int[]，每个元素 0xAARRGGBB）
 * @param width 图像宽度
 * @param height 图像高度
 * @param playerLeft 玩家左边界（归一化）
 * @param playerRight 玩家右边界（归一化）
 * @param playerWidth 玩家宽度（归一化）
 * @param playerCenterX 玩家中心 X（归一化）
 * @param playerCenterY 玩家中心 Y（归一化）
 * @return 检测结果 byte[]（VisionGreenBottleResult 结构体二进制）
 */
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_top_azek431_hzzs_core_data_native_VisionBridge_detectGreenBottle(
    JNIEnv* env, jclass, jintArray rgbPixels,
    jint width, jint height,
    jfloat playerLeft, jfloat playerRight, jfloat playerWidth,
    jfloat playerCenterX, jfloat playerCenterY) {

    // 参数校验：像素数组不能为空
    if (rgbPixels == nullptr) {
        LOGE("[JNI] detectGreenBottle: rgbPixels is null");
        return structToByteArray(env, nullptr, 0);
    }

    // 读取像素数据前先校验 Java 数组长度，防止越界读取
    int javaArrayLen = env->GetArrayLength(rgbPixels);
    int expectedPixelCount = width * height;
    if (javaArrayLen < expectedPixelCount || expectedPixelCount <= 0) {
        LOGW("[JNI] detectGreenBottle: pixel mismatch javaLen=%d expected=%d (w=%d h=%d), skipping.",
             javaArrayLen, expectedPixelCount, width, height);
        // 返回全零结果（未检测到）
        hzzs::vision::VisionGreenBottleResult emptyResult{};
        emptyResult.found = false;
        return structToByteArray(env, &emptyResult, sizeof(emptyResult));
    }

    jint* pixels = env->GetIntArrayElements(rgbPixels, nullptr);

    // 转换为 uint8_t* RGB
    uint8_t* rgb = new uint8_t[expectedPixelCount * 3];
    for (int i = 0; i < expectedPixelCount; i++) {
        jint p = pixels[i];
        rgb[i * 3 + 0] = static_cast<uint8_t>((p >> 16) & 0xFF);  // R
        rgb[i * 3 + 1] = static_cast<uint8_t>((p >> 8) & 0xFF);   // G
        rgb[i * 3 + 2] = static_cast<uint8_t>(p & 0xFF);          // B
    }
    env->ReleaseIntArrayElements(rgbPixels, pixels, JNI_ABORT);

    // 3. 构建玩家参考框
    hzzs::vision::VisionPlayerRef player{};
    player.left_x = static_cast<int32_t>(playerLeft * width);
    player.right_x = static_cast<int32_t>(playerRight * width);
    player.width_px = static_cast<int32_t>(playerWidth * width);
    player.center_x = static_cast<int32_t>(playerCenterX * width);
    player.center_y = static_cast<int32_t>(playerCenterY * height);

    // 4. 调用 C++ 算法
    hzzs::vision::VisionGreenBottleResult result{};
    hzzs::vision::detectGreenBottle(rgb, width, height, player,
                                    hzzs::vision::getDefaultParams(), result);

    // 5. 记录日志
    HzzsLogEntry logEntry{};
    std::memset(&logEntry, 0, sizeof(logEntry));
    logEntry.type = HZZS_LOG_ENTRY_DETECTION;
    logEntry.width = width;
    logEntry.height = height;
    logEntry.total_cost_ms = result.cost_ms;
    logEntry.bottle_found = result.found ? 1 : 0;
    logEntry.bottle_left_x = result.left_x;
    logEntry.bottle_right_x = result.right_x;
    logEntry.bottle_center_x = result.center_x;
    logEntry.bottle_width_px = result.width_px;
    logEntry.bottle_edge_gap_px = result.edge_gap_px;
    logEntry.bottle_confidence = result.confidence;
    logEntry.bottle_cost_ms = result.cost_ms;
    hzzs_log_append(&logEntry);

    // 6. 返回结果
    delete[] rgb;
    return structToByteArray(env, &result, sizeof(result));
}

/**
 * 坑位检测 JNI 入口。
 */
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_top_azek431_hzzs_core_data_native_VisionBridge_detectPit(
    JNIEnv* env, jclass, jintArray rgbPixels,
    jint width, jint height,
    jfloat playerLeft, jfloat playerRight, jfloat playerWidth,
    jfloat playerCenterX, jfloat playerCenterY) {

    // 参数校验：像素数组不能为空
    if (rgbPixels == nullptr) {
        LOGE("[JNI] detectPit: rgbPixels is null");
        return structToByteArray(env, nullptr, 0);
    }

    // 读取像素数据前先校验 Java 数组长度，防止越界读取
    int javaArrayLen = env->GetArrayLength(rgbPixels);
    int expectedPixelCount = width * height;
    if (javaArrayLen < expectedPixelCount || expectedPixelCount <= 0) {
        LOGW("[JNI] detectPit: pixel mismatch javaLen=%d expected=%d (w=%d h=%d), skipping.",
             javaArrayLen, expectedPixelCount, width, height);
        hzzs::vision::VisionPitResult emptyResult{};
        emptyResult.found = false;
        return structToByteArray(env, &emptyResult, sizeof(emptyResult));
    }

    jint* pixels = env->GetIntArrayElements(rgbPixels, nullptr);

    uint8_t* rgb = new uint8_t[expectedPixelCount * 3];
    for (int i = 0; i < expectedPixelCount; i++) {
        jint p = pixels[i];
        rgb[i * 3 + 0] = static_cast<uint8_t>((p >> 16) & 0xFF);
        rgb[i * 3 + 1] = static_cast<uint8_t>((p >> 8) & 0xFF);
        rgb[i * 3 + 2] = static_cast<uint8_t>(p & 0xFF);
    }
    env->ReleaseIntArrayElements(rgbPixels, pixels, JNI_ABORT);

    hzzs::vision::VisionPlayerRef player{};
    player.left_x = static_cast<int32_t>(playerLeft * width);
    player.right_x = static_cast<int32_t>(playerRight * width);
    player.width_px = static_cast<int32_t>(playerWidth * width);
    player.center_x = static_cast<int32_t>(playerCenterX * width);
    player.center_y = static_cast<int32_t>(playerCenterY * height);

    hzzs::vision::VisionPitResult result{};
    hzzs::vision::detectPit(rgb, width, height, player,
                            hzzs::vision::getDefaultParams(), result);

    // 记录日志
    HzzsLogEntry logEntry{};
    std::memset(&logEntry, 0, sizeof(logEntry));
    logEntry.type = HZZS_LOG_ENTRY_DETECTION;
    logEntry.width = width;
    logEntry.height = height;
    logEntry.total_cost_ms = result.cost_ms;
    logEntry.pit_found = result.found ? 1 : 0;
    logEntry.pit_left_x = result.left_x;
    logEntry.pit_right_x = result.right_x;
    logEntry.pit_center_x = result.center_x;
    logEntry.pit_width_px = result.width_px;
    logEntry.pit_edge_gap_px = result.edge_gap_px;
    logEntry.pit_confidence = result.confidence;
    logEntry.pit_cost_ms = result.cost_ms;
    hzzs_log_append(&logEntry);

    delete[] rgb;
    return structToByteArray(env, &result, sizeof(result));
}

/**
 * 绘制调试图 JNI 入口。
 *
 * @param rgbPixels RGB 像素数组
 * @param width 图像宽度
 * @param height 图像高度
 * @param player 玩家参考框数据（byte[]）
 * @param bottle 绿瓶检测结果（byte[]）
 * @param pit 坑位检测结果（byte[]）
 * @return RGBA 像素数组（byte[]）
 */
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_top_azek431_hzzs_core_data_native_VisionBridge_renderDebugImage(
    JNIEnv* env, jclass, jintArray rgbPixels,
    jint width, jint height,
    jbyteArray playerData, jbyteArray bottleData, jbyteArray pitData) {

    // 参数校验：像素数组不能为空
    if (rgbPixels == nullptr) {
        LOGE("[JNI] renderDebugImage: rgbPixels is null");
        return env->NewByteArray(0);
    }

    int javaArrayLen = env->GetArrayLength(rgbPixels);
    int expectedPixelCount = width * height;
    if (javaArrayLen < expectedPixelCount || expectedPixelCount <= 0) {
        LOGW("[JNI] renderDebugImage: pixel mismatch javaLen=%d expected=%d, returning empty.",
             javaArrayLen, expectedPixelCount);
        return env->NewByteArray(0);
    }

    jint* pixels = env->GetIntArrayElements(rgbPixels, nullptr);

    uint8_t* rgb = new uint8_t[expectedPixelCount * 3];
    for (int i = 0; i < expectedPixelCount; i++) {
        jint p = pixels[i];
        rgb[i * 3 + 0] = static_cast<uint8_t>((p >> 16) & 0xFF);
        rgb[i * 3 + 1] = static_cast<uint8_t>((p >> 8) & 0xFF);
        rgb[i * 3 + 2] = static_cast<uint8_t>(p & 0xFF);
    }
    env->ReleaseIntArrayElements(rgbPixels, pixels, JNI_ABORT);

    // 构建结果
    hzzs::vision::VisionFrameResult result{};
    std::memset(&result, 0, sizeof(result));
    result.width = width;
    result.height = height;

    byteArrayToStruct(env, playerData, &result.player, sizeof(result.player));
    byteArrayToStruct(env, bottleData, &result.bottle, sizeof(result.bottle));
    byteArrayToStruct(env, pitData, &result.pit, sizeof(result.pit));

    // 生成 RGBA 输出
    uint8_t* rgba = new uint8_t[expectedPixelCount * 4];
    for (int i = 0; i < expectedPixelCount; i++) {
        rgba[i * 4 + 0] = rgb[i * 3 + 0];  // R
        rgba[i * 4 + 1] = rgb[i * 3 + 1];  // G
        rgba[i * 4 + 2] = rgb[i * 3 + 2];  // B
        rgba[i * 4 + 3] = 255;             // A
    }

    hzzs::vision::renderDebugImage(rgba, rgb, width, height, &result, rgba);

    jbyteArray output = env->NewByteArray(expectedPixelCount * 4);
    env->SetByteArrayRegion(output, 0, expectedPixelCount * 4,
                            reinterpret_cast<const jbyte*>(rgba));

    delete[] rgb;
    delete[] rgba;
    return output;
}

/**
 * 获取日志 CSV 字符串。
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_top_azek431_hzzs_core_data_native_VisionBridge_getLogCsv(
    JNIEnv* env, jclass) {
    char buffer[65536];
    int len = hzzs_log_export_csv(buffer, sizeof(buffer));
    if (len <= 0) return env->NewStringUTF("");
    return env->NewStringUTF(buffer);
}

/**
 * 获取日志 JSON 字符串。
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_top_azek431_hzzs_core_data_native_VisionBridge_getLogJson(
    JNIEnv* env, jclass) {
    char buffer[65536];
    int len = hzzs_log_export_json(buffer, sizeof(buffer));
    if (len <= 0) return env->NewStringUTF("[]");
    return env->NewStringUTF(buffer);
}

/**
 * 获取日志条目数量。
 */
extern "C"
JNIEXPORT jint JNICALL
Java_top_azek431_hzzs_core_data_native_VisionBridge_getLogCount(
    JNIEnv*, jclass) {
    return hzzs_log_count();
}

/**
 * 清空日志。
 */
extern "C"
JNIEXPORT void JNICALL
Java_top_azek431_hzzs_core_data_native_VisionBridge_clearLog(
    JNIEnv*, jclass) {
    hzzs_log_clear();
}
