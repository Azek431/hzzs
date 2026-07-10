// 火崽崽助手（HZZS）视觉识别 — 坑位行扫描检测器。
//
// 职责：
// - 在指定扫描线上扫描可站立地面断裂（坑位）
// - 使用地面颜色规则判断可站立像素
// - 聚合连续地面段，找出玩家右侧相邻地面段之间的断裂
// - 输出归一化坐标、置信度、成本等信息
//
// 算法流程：
// 1. pitScanY = height * 0.599
// 2. 对每个 x，在 pitScanY ± 3 行统计地面像素比例
// 3. groundRatio >= 0.45 则认为该 x 是地面
// 4. 收集连续地面段
// 5. 合并 <=5px 的小断裂
// 6. 过滤太短地面段
// 7. 找玩家右侧相邻两个长地面段之间的非地面缺口
// 8. 计算坑位左/右/中心/宽度
// 9. 过滤坑宽
// 10. 输出结果
//
// 线程模型：
// - 无状态，可安全在任意线程调用
// - 检测耗时通常在 2~8ms 以内

package top.azek431.hzzs.data.vision

import android.graphics.Color

/**
 * 坑位行扫描检测器。
 *
 * 在指定扫描线上执行地面断裂检测。
 *
 * @param groundRMin R 通道下限（默认 220）
 * @param groundGMin G 通道下限（默认 212）
 * @param groundBMin B 通道下限（默认 190）
 * @param groundChromaMax 最大色差（默认 70）
 * @param groundBandHalfHeight 地面搜索带半高（默认 3 行）
 * @param groundRatioMin 地面像素比例下限（默认 0.45）
 * @param groundMergeGapPx 地面段合并间隙（默认 5px）
 * @param minGroundWidth 最小地面段宽度（默认 playerWidth * 0.6）
 * @param pitMinWidth 最小坑位宽度（默认 width * 0.01）
 * @param pitMaxWidth 最大坑位宽度（默认 width * 0.12）
 * @param confidenceThreshold 置信度阈值（默认 0.70）
 */
class PitLineDetector(
    private val groundRMin: Int = 220,
    private val groundGMin: Int = 212,
    private val groundBMin: Int = 190,
    private val groundChromaMax: Int = 70,
    private val groundBandHalfHeight: Int = 3,
    private val groundRatioMin: Float = 0.45f,
    private val groundMergeGapPx: Int = 5,
    private val minGroundWidth: Int = 40,
    private val pitMinWidth: Int = 6,
    private val pitMaxWidth: Int = 80,
    private val confidenceThreshold: Float = 0.70f,
) : VisionDetector {

    override val name: String = "PitLineDetector"

    /**
     * 执行坑位检测（VisionDetector 接口实现）。
     *
     * @param frame 画面帧数据
     * @param playerReference 玩家参考坐标
     * @return 坑位检测结果
     */
    override fun detect(frame: VisionFrame, playerReference: PlayerReference): DetectionResult {
        return detectPit(frame, playerReference)
    }

    /**
     * 执行坑位检测（具体实现）。
     *
     * @param frame 画面帧数据
     * @param playerReference 玩家参考坐标
     * @return 坑位检测结果
     */
    fun detectPit(frame: VisionFrame, playerReference: PlayerReference): PitDetection {
        val startTime = System.nanoTime()

        if (!playerReference.isValid) {
            return PitDetection(
                detector = this, detected = false, confidence = 0f, costMs = 0f,
            )
        }

        val w = frame.width
        val h = frame.height
        val pixels = frame.pixels
        val playerRightPx = (playerReference.right * w).toInt()
        val playerWidthPx = (playerReference.width * w).toInt()

        // 计算扫描参数
        val scanY = (h * 0.599f).toInt().coerceIn(0, h - 1)
        val effectiveMinGround = maxOf(minGroundWidth, (playerWidthPx * 0.6f).toInt())
        val effectivePitMin = maxOf(pitMinWidth, (w * 0.01f).toInt())
        val effectivePitMax = minOf(pitMaxWidth, (w * 0.12f).toInt())

        // 扫描地面
        val groundSegments = scanGroundLine(pixels, w, h, scanY)

        // 合并小断裂
        val merged = mergeGroundSegments(groundSegments, groundMergeGapPx)

        // 过滤太短的地面段
        val validGrounds = merged.filter { it.width >= effectiveMinGround }

        // 在玩家右侧找相邻两个长地面段之间的坑位
        val playerSideGrounds = validGrounds.filter { it.left >= playerRightPx }
        val pit = findPitBetweenGrounds(
            playerSideGrounds, playerRightPx,
            effectivePitMin, effectivePitMax,
            pixels, w, h, scanY,
        )

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000f

        if (pit == null) {
            return PitDetection(
                detector = this, detected = false, confidence = 0f, costMs = elapsedMs,
            )
        }

        // 计算置信度
        val widthScore = (1f - (pit.width.toFloat() / effectivePitMax)).coerceIn(0f, 1f)
        val ratioScore = (pit.groundRatioAbove.toFloat() / maxOf(2, pit.groundSamples)).coerceIn(0f, 1f)
        val confidence = (widthScore * 0.5f + ratioScore * 0.5f).coerceIn(0f, 1f)

        if (confidence < confidenceThreshold) {
            return PitDetection(
                detector = this, detected = false, confidence = confidence, costMs = elapsedMs,
            )
        }

        return PitDetection(
            detector = this,
            detected = true,
            confidence = confidence,
            costMs = elapsedMs,
            left = pit.left.toFloat() / w,
            right = pit.right.toFloat() / w,
            center = pit.center.toFloat() / w,
            width = pit.width.toFloat() / w,
            scanY = scanY,
        )
    }

    /**
     * 扫描单行地面像素。
     *
     * 对每个 x 坐标，在扫描线 ±bandHalfHeight 范围内统计地面像素比例。
     * 比例 >= groundRatioMin 则认为该 x 是地面。
     *
     * @return 连续地面段列表
     */
    private fun scanGroundLine(
        pixels: IntArray, width: Int, height: Int, scanY: Int,
    ): List<GroundSegment> {
        val segments = mutableListOf<GroundSegment>()
        var segStart = -1
        var totalRatio = 0
        var count = 0

        for (x in 0 until width) {
            val ratio = calculateGroundRatio(pixels, width, height, x, scanY)
            if (ratio >= groundRatioMin) {
                if (segStart < 0) segStart = x
                totalRatio += ratio.toInt()
                count++
            } else {
                if (segStart >= 0) {
                    segments.add(GroundSegment(segStart, x - 1, (totalRatio / count.toFloat()).toInt()))
                    segStart = -1
                    totalRatio = 0
                    count = 0
                }
            }
        }
        if (segStart >= 0) {
            segments.add(GroundSegment(segStart, width - 1, (totalRatio / maxOf(1, count).toFloat()).toInt()))
        }

        return segments
    }

    /**
     * 计算单个 x 坐标的地面像素比例。
     *
     * 在 scanY ± bandHalfHeight 范围内逐行检查。
     *
     * @return 地面像素比例（0.0 ~ 1.0）
     */
    private fun calculateGroundRatio(
        pixels: IntArray, width: Int, height: Int, x: Int, scanY: Int,
    ): Float {
        var groundCount = 0
        var totalCount = 0

        for (dy in -groundBandHalfHeight..groundBandHalfHeight) {
            val y = (scanY + dy).coerceIn(0, height - 1)
            val pixel = pixels[y * width + x.coerceIn(0, width - 1)]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)

            totalCount++
            if (isGroundPixel(r, g, b)) {
                groundCount++
            }
        }

        return groundCount.toFloat() / totalCount
    }

    /**
     * 判断是否为地面像素。
     *
     * 地面颜色规则（暖色系浅色地面）：
     * - R > 220
     * - G > 212
     * - B > 190
     * - max(R,G,B) - min(R,G,B) < 70（低色差，接近纯色）
     */
    private fun isGroundPixel(r: Int, g: Int, b: Int): Boolean {
        return r > groundRMin &&
            g > groundGMin &&
            b > groundBMin &&
            (maxOf(r, g, b) - minOf(r, g, b)) < groundChromaMax
    }

    /**
     * 合并相邻的小断裂地面段。
     */
    private fun mergeGroundSegments(segments: List<GroundSegment>, gapPx: Int): List<GroundSegment> {
        if (segments.size <= 1) return segments

        val merged = mutableListOf<GroundSegment>()
        var current = segments[0]

        for (i in 1 until segments.size) {
            val next = segments[i]
            if (next.left - current.right <= gapPx) {
                // 合并：加权平均地面比例
                val avgRatio = (current.ratioAvg * current.width + next.ratioAvg * next.width)
                    .toFloat() / (current.width + next.width)
                current = GroundSegment(current.left, next.right, avgRatio.toInt())
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    /**
     * 在玩家右侧相邻地面段之间寻找坑位。
     *
     * 找法：按从左到右排序地面段，找到玩家右侧第一个间隙，
     * 其宽度在 [pitMin, pitMax] 范围内且间隙上方有一定比例非地面。
     *
     * @return 坑位信息，未找到返回 null
     */
    private fun findPitBetweenGrounds(
        grounds: List<GroundSegment>, playerRightPx: Int,
        pitMin: Int, pitMax: Int,
        pixels: IntArray, width: Int, height: Int, scanY: Int,
    ): PitInfo? {
        // 按左边界排序
        val sorted = grounds.sortedBy { it.left }

        // 找玩家右侧第一个间隙
        for (i in 0 until sorted.size - 1) {
            val g1 = sorted[i]
            val g2 = sorted[i + 1]

            // 间隙必须在玩家右侧
            if (g1.right < playerRightPx) continue

            val gapLeft = g1.right + 1
            val gapRight = g2.left - 1
            val gapWidth = gapRight - gapLeft + 1

            if (gapWidth < pitMin || gapWidth > pitMax) continue

            // 验证间隙上方确实是非地面（采样几行确认）
            val sampleX = (gapLeft + gapRight) / 2
            var nonGroundCount = 0
            var sampleCount = 0
            for (dy in -2..2) {
                val y = (scanY + dy).coerceIn(0, height - 1)
                val x = sampleX.coerceIn(0, width - 1)
                val pixel = pixels[y * width + x]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                sampleCount++
                if (!isGroundPixel(r, g, b)) {
                    nonGroundCount++
                }
            }

            return PitInfo(
                left = gapLeft,
                right = gapRight,
                width = gapWidth,
                groundRatioAbove = nonGroundCount,
                groundSamples = sampleCount,
            )
        }

        return null
    }

    /**
     * 坑位信息。
     */
    private data class PitInfo(
        val left: Int,
        val right: Int,
        val width: Int,
        val groundRatioAbove: Int,
        val groundSamples: Int,
    ) {
        val center: Int get() = (left + right) / 2
    }

    /**
     * 地面段。
     */
    private data class GroundSegment(
        val left: Int,
        val right: Int,
        val ratioAvg: Int,
    ) {
        val width: Int get() = right - left + 1
    }
}
