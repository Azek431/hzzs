package top.azek431.hzzs.runtime.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import top.azek431.hzzs.runtime.capture.CapturePreferences
import kotlin.math.roundToInt

object FrameNormalizer {
    private var reusablePixels = IntArray(0)

    /** 单线程实时控制器调用；同步保证复用缓冲区不会被并发覆盖。 */
    @Synchronized
    fun normalize(
        context: Context,
        source: Bitmap,
        maxWorkWidth: Int = 480,
    ): NormalizedFrame {
        require(source.width >= 2 && source.height >= 2) { "截图尺寸无效：${source.width}x${source.height}" }
        require(maxWorkWidth >= 32) { "工作宽度过小：$maxWorkWidth" }

        val viewportNorm = CapturePreferences.getViewport(context)
        val left = (viewportNorm.left * source.width).roundToInt().coerceIn(0, source.width - 2)
        val top = (viewportNorm.top * source.height).roundToInt().coerceIn(0, source.height - 2)
        val right = (viewportNorm.right * source.width).roundToInt().coerceIn(left + 2, source.width)
        val bottom = (viewportNorm.bottom * source.height).roundToInt().coerceIn(top + 2, source.height)
        val viewport = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

        var crop: Bitmap? = null
        var work: Bitmap? = null
        try {
            crop = if (left == 0 && top == 0 && right == source.width && bottom == source.height) {
                source
            } else {
                Bitmap.createBitmap(source, left, top, right - left, bottom - top)
            }
            work = if (crop.width > maxWorkWidth) {
                // 图色算法优先保持离散颜色边界，禁用双线性插值。
                Bitmap.createScaledBitmap(
                    crop,
                    maxWorkWidth,
                    (crop.height * maxWorkWidth.toFloat() / crop.width).roundToInt().coerceAtLeast(1),
                    false,
                )
            } else {
                crop
            }

            val required = work.width * work.height
            if (reusablePixels.size != required) reusablePixels = IntArray(required)
            work.getPixels(reusablePixels, 0, work.width, 0, 0, work.width, work.height)

            return NormalizedFrame(
                pixels = reusablePixels,
                width = work.width,
                height = work.height,
                mapping = FrameMapping(
                    viewport = viewport,
                    sourceWidth = source.width,
                    sourceHeight = source.height,
                    scaleToSourceX = viewport.width() / work.width,
                    scaleToSourceY = viewport.height() / work.height,
                ),
            )
        } finally {
            if (work != null && work !== crop && !work.isRecycled) work.recycle()
            if (crop != null && crop !== source && !crop.isRecycled) crop.recycle()
        }
    }
}
