// 火崽崽助手（HZZS）视觉识别 JNI 桥接。
//
// 封装 C++ 视觉算法（绿瓶检测 + 坑位检测 + 绘制 + 日志）的 JNI 调用。
// 所有方法都是 static native，直接映射到 C++ 的 JNI 导出函数。

package top.azek431.hzzs.core.data.native

import top.azek431.hzzs.core.model.FrameAnalysisResult
import top.azek431.hzzs.core.model.RectF

/**
 * 视觉识别 JNI 桥接。
 *
 * 封装 C++ 视觉算法的 JNI 调用：
 * - detectGreenBottle：绿瓶检测
 * - detectPit：坑位检测
 * - renderDebugImage：绘制调试图
 * - getLogCsv/getLogJson/getLogCount/clearLog：日志管理
 *
 * 注意：native 库由 NativeLibraryLoader 统一加载，此处不再重复 System.loadLibrary。
 */
object VisionBridge {

    // ==================== 绿瓶检测 ====================

    /**
     * 检测绿瓶。
     *
     * @param rgbPixels RGB 像素数组（int[]，每个元素 0xAARRGGBB）
     * @param width 图像宽度
     * @param height 图像高度
     * @param playerBounds 玩家矩形（归一化坐标）
     * @return 检测结果 byte[]（VisionGreenBottleResult 结构体二进制）
     */
    external fun detectGreenBottle(
        rgbPixels: IntArray,
        width: Int,
        height: Int,
        playerLeft: Float,
        playerRight: Float,
        playerWidth: Float,
        playerCenterX: Float,
        playerCenterY: Float,
    ): ByteArray

    // ==================== 坑位检测 ====================

    /**
     * 检测坑位。
     */
    external fun detectPit(
        rgbPixels: IntArray,
        width: Int,
        height: Int,
        playerLeft: Float,
        playerRight: Float,
        playerWidth: Float,
        playerCenterX: Float,
        playerCenterY: Float,
    ): ByteArray

    // ==================== 绘制 ====================

    /**
     * 绘制调试图。
     *
     * @param rgbPixels RGB 像素数组
     * @param width 图像宽度
     * @param height 图像高度
     * @param playerData 玩家数据（byte[]）
     * @param bottleData 绿瓶检测结果（byte[]）
     * @param pitData 坑位检测结果（byte[]）
     * @return RGBA 像素数组（byte[]）
     */
    external fun renderDebugImage(
        rgbPixels: IntArray,
        width: Int,
        height: Int,
        playerData: ByteArray,
        bottleData: ByteArray,
        pitData: ByteArray,
    ): ByteArray

    // ==================== 日志 ====================

    /** 获取日志 CSV 字符串 */
    external fun getLogCsv(): String

    /** 获取日志 JSON 字符串 */
    external fun getLogJson(): String

    /** 获取日志条目数量 */
    external fun getLogCount(): Int

    /** 清空日志 */
    external fun clearLog()
}
