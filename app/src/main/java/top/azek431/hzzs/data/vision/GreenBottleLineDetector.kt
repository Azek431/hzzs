// 火崽崽助手（HZZS）视觉识别 — 绿瓶行扫描检测器。
//
// 职责：
// - 在玩家中心 Y 水平线上，从玩家右侧开始向右扫描绿色像素
// - 使用 RGB 色差条件判断绿色像素
// - 聚合连续绿色像素为片段，合并相邻片段
// - 输出归一化坐标、置信度、成本等信息
//
// 算法流程：
// 1. scanStartX = playerRight + playerWidth * 0.15
// 2. scanEndX = width - 20
// 3. 扫描 greenScanY 一行
// 4. 收集连续绿色段
// 5. 合并小缺口（maxGap = playerWidth * 0.25）
// 6. 过滤候选宽度
// 7. 取玩家右侧最近候选
// 8. padding = playerWidth * 0.065，限制 4~10px
// 9. 输出结果
//
// 线程模型：
// - 无状态，可安全在任意线程调用
// - 检测耗时通常在 1~5ms 以内

package top.azek431.hzzs.data.vision

import android.graphics.Color

/**
 * 绿瓶行扫描检测器。
 *
 * 在指定扫描线上执行 RGB 绿色像素检测。
 *
 * @param rgbGMin G 通道下限（默认 120）
 * @param rgbRMin R 通道下限（默认 30）
 * @param rgbBMin B 通道下限（默认 30）
 * @param minSegmentWidth 最小段宽（像素）
 * @param gapMergeWidth 小缺口合并宽度（像素）
 * @param detectionPadding 检测区域 padding（像素）
 * @param confidenceThreshold 置信度阈值
 */
class GreenBottleLineDetector(
    private val rgbGMin: Int = 120,
    private val rgbRMin: Int = 30,
    private val rgbBMin: Int = 30,
    private val minSegmentWidth: Int = 3,
    private val gapMergeWidth: Int = 5,
    private val detectionPadding: Int = 10,
    private val confidenceThreshold: Float = 0.5f,
) : VisionDetector {

    override val name: String = "GreenBottleLineDetector"

    /**
     * 执行绿瓶检测（VisionDetector 接口实现）。
     *
     * @param frame 画面帧数据
     * @param playerReference 玩家参考坐标
     * @return 绿瓶检测结果
     */
    override fun detect(frame: VisionFrame, playerReference: PlayerReference): DetectionResult {
        return detectGreenBottle(frame, playerReference)
    }

    /**
     * 执行绿瓶检测（具体实现）。
     *
     * @param frame 画面帧数据
     * @param playerReference 玩家参考坐标
     * @return 绿瓶检测结果
     */
    fun detectGreenBottle(frame: VisionFrame, playerReference: PlayerReference): GreenBottleDetection {
        val startTime = System.nanoTime()

        // 前置条件检查
        if (!playerReference.isValid) {
            return GreenBottleDetection(
                detector = this, detected = false, confidence = 0f, costMs = 0f,
            )
        }

        val w = frame.width
        val h = frame.height
        val pixels = frame.pixels

        // 计算扫描参数
        val playerLeftPx = (playerReference.left * w).toInt()
        val playerRightPx = (playerReference.right * w).toInt()
        val playerWidthPx = (playerReference.width * w).toInt()
        val scanY = (playerReference.centerY * h).toInt().coerceIn(0, h - 1)

        val scanStartX = (playerRightPx + playerWidthPx * 0.15f).toInt().coerceAtMost(w - 20)
        val scanEndX = w - 20

        // 执行行扫描
        val segments = scanGreenLine(pixels, w, scanY, scanStartX, scanEndX)

        // 合并小缺口
        val merged = mergeSegments(segments, gapMergeWidth)

        // 过滤候选
        val candidates = merged.filter { it.width >= minSegmentWidth }

        // 取最近的候选
        val closest = candidates.minByOrNull { it.left - playerRightPx }

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000f

        if (closest == null) {
            return GreenBottleDetection(
                detector = this, detected = false, confidence = 0f, costMs = elapsedMs,
            )
        }

        // 计算置信度
        val widthScore = (closest.width.toFloat() / (playerWidthPx * 0.5f)).coerceIn(0f, 1f)
        val chromaScore = calculateChromaScore(pixels, closest, w)
        val confidence = (widthScore * 0.4f + chromaScore * 0.6f).coerceIn(0f, 1f)

        if (confidence < confidenceThreshold) {
            return GreenBottleDetection(
                detector = this, detected = false, confidence = confidence, costMs = elapsedMs,
            )
        }

        val padding = (playerWidthPx * 0.065f).toInt().coerceIn(4, 10)
        val leftX = closest.left + padding
        val rightX = closest.right - padding
        val centerX = (leftX + rightX) / 2

        return GreenBottleDetection(
            detector = this,
            detected = true,
            confidence = confidence,
            costMs = elapsedMs,
            leftX = leftX.toFloat() / w,
            rightX = rightX.toFloat() / w,
            centerX = centerX.toFloat() / w,
            scanY = scanY,
            edgeGapPx = leftX - playerRightPx,
        )
    }

    /**
     * 扫描单行绿色像素。
     *
     * @return 连续绿色段列表（按从左到右排序）
     */
    private fun scanGreenLine(
        pixels: IntArray, width: Int, y: Int, startX: Int, endX: Int,
    ): List<GreenSegment> {
        val rawSegments = mutableListOf<GreenSegment>()
        var segStart = -1

        for (x in startX..endX) {
            if (x >= width) break
            val pixel = pixels[y * width + x]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)

            if (isGreenPixel(r, g, b)) {
                if (segStart < 0) segStart = x
            } else {
                if (segStart >= 0) {
                    rawSegments.add(GreenSegment(segStart, x - 1))
                    segStart = -1
                }
            }
        }
        if (segStart >= 0) rawSegments.add(GreenSegment(segStart, endX.coerceAtMost(width - 1)))

        return rawSegments
    }

    /**
     * 判断是否为绿色像素。
     *
     * RGB 绿色规则：
     * - G > rgbGMin
     * - G - R > 15
     * - G - B > 30
     * - max(R,G,B) - min(R,G,B) > 45
     */
    private fun isGreenPixel(r: Int, g: Int, b: Int): Boolean {
        return g > rgbGMin &&
            (g - r) > 15 &&
            (g - b) > 30 &&
            (maxOf(r, g, b) - minOf(r, g, b)) > 45
    }

    /**
     * 合并相邻的小缺口。
     *
     * 如果两个绿色段之间的距离 <= gapPx，则合并为一个段。
     */
    private fun mergeSegments(segments: List<GreenSegment>, gapPx: Int): List<GreenSegment> {
        if (segments.size <= 1) return segments

        val merged = mutableListOf<GreenSegment>()
        var current = segments[0]

        for (i in 1 until segments.size) {
            val next = segments[i]
            if (next.left - current.right <= gapPx) {
                current = GreenSegment(current.left, next.right)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    /**
     * 计算色度得分（0.0 ~ 1.0）。
     *
     * 绿色段的色彩纯度越高，得分越高。
     */
    private fun calculateChromaScore(pixels: IntArray, segment: GreenSegment, width: Int): Float {
        var totalChroma = 0
        var count = 0
        for (x in segment.left..segment.right) {
            if (x >= width) break
            val pixel = pixels[(segment.y ?: 0) * width + x]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            totalChroma += maxOf(r, g, b) - minOf(r, g, b)
            count++
        }
        return if (count > 0) (totalChroma / count.toFloat() / 255f).coerceIn(0f, 1f) else 0f
    }

    /**
     * 绿色段。
     *
     * @property left 左边界 X（像素）
     * @property right 右边界 X（像素）
     * @property y 扫描线 Y（像素），可为 null（表示未知）
     */
    private data class GreenSegment(
        val left: Int,
        val right: Int,
        val y: Int? = null,
    ) {
        val width: Int get() = right - left + 1
    }
}
