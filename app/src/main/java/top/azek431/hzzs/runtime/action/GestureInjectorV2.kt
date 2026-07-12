package top.azek431.hzzs.runtime.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import kotlin.random.Random

object GestureInjectorV2 {
    fun inject(service: AccessibilityService, action: RuntimeAction, metrics: DisplayMetrics, callback: AccessibilityService.GestureResultCallback? = null): Boolean {
        val w = metrics.widthPixels.toFloat(); val h = metrics.heightPixels.toFloat()
        val builder = GestureDescription.Builder()
        when (action.type) {
            RuntimeActionType.JUMP -> {
                val x = w * Random.nextDouble(0.40, 0.60).toFloat()
                val y = h * Random.nextDouble(0.45, 0.65).toFloat()
                val duration = Random.nextLong(22L, 43L)
                builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0L, duration))
            }
            RuntimeActionType.SLIDE -> {
                val x1 = w * Random.nextDouble(0.43, 0.57).toFloat()
                val y1 = h * Random.nextDouble(0.45, 0.55).toFloat()
                val x2 = (x1 + w * Random.nextDouble(-0.035, 0.035).toFloat()).coerceIn(w * .15f, w * .85f)
                val y2 = h * Random.nextDouble(0.68, 0.78).toFloat()
                val duration = Random.nextLong(170L, 251L)
                builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0L, duration))
            }
        }
        return service.dispatchGesture(builder.build(), callback, null)
    }
}
