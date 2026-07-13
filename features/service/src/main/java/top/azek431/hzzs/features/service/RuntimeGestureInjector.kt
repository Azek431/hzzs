package top.azek431.hzzs.features.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import kotlin.random.Random

/** 将实时视觉动作转换为系统无障碍手势。 */
object RuntimeGestureInjector {
    fun inject(
        service: AccessibilityService,
        action: RuntimeAction,
        metrics: DisplayMetrics,
        callback: AccessibilityService.GestureResultCallback? = null,
    ): Boolean {
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        if (width <= 0f || height <= 0f) return false

        val builder = GestureDescription.Builder()
        when (action.type) {
            RuntimeActionType.JUMP -> {
                val x = width * Random.nextDouble(0.40, 0.60).toFloat()
                val y = height * Random.nextDouble(0.45, 0.65).toFloat()
                val duration = Random.nextLong(22L, 43L)
                builder.addStroke(
                    GestureDescription.StrokeDescription(
                        Path().apply { moveTo(x, y) },
                        0L,
                        duration,
                    )
                )
            }

            RuntimeActionType.SLIDE -> {
                val startX = width * Random.nextDouble(0.43, 0.57).toFloat()
                val startY = height * Random.nextDouble(0.45, 0.55).toFloat()
                val endX = (startX + width * Random.nextDouble(-0.035, 0.035).toFloat())
                    .coerceIn(width * 0.15f, width * 0.85f)
                val endY = height * Random.nextDouble(0.68, 0.78).toFloat()
                val duration = Random.nextLong(170L, 251L)
                builder.addStroke(
                    GestureDescription.StrokeDescription(
                        Path().apply {
                            moveTo(startX, startY)
                            lineTo(endX, endY)
                        },
                        0L,
                        duration,
                    )
                )
            }
        }
        return service.dispatchGesture(builder.build(), callback, null)
    }
}
