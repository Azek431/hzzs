package top.azek431.hzzs.runtime.settings

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import top.azek431.hzzs.runtime.capture.CapturePreferences
import kotlin.math.max
import kotlin.math.min

/** 全屏透明标定：点击游戏画面的左上角和右下角。 */
class ViewportCalibrationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        setContentView(CalibrationView())
    }

    inner class CalibrationView : View(this) {
        private val points = ArrayList<PointF>(2)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.CYAN
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.argb(90, 0, 0, 0))
            paint.textSize = 46f
            paint.style = Paint.Style.FILL
            canvas.drawText(
                if (points.isEmpty()) "点击游戏画面左上角" else "点击游戏画面右下角",
                28f,
                80f,
                paint,
            )
            paint.style = Paint.Style.STROKE
            points.firstOrNull()?.let { point ->
                canvas.drawCircle(point.x, point.y, 20f, paint)
                canvas.drawLine(point.x - 30f, point.y, point.x + 30f, point.y, paint)
                canvas.drawLine(point.x, point.y - 30f, point.x, point.y + 30f, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true
            points += PointF(event.x, event.y)
            invalidate()
            if (points.size < 2) return true

            val first = points[0]
            val second = points[1]
            val raw = RectF(
                min(first.x, second.x),
                min(first.y, second.y),
                max(first.x, second.x),
                max(first.y, second.y),
            )
            if (raw.width() < width * MIN_WIDTH_RATIO || raw.height() < height * MIN_HEIGHT_RATIO) {
                points.clear()
                Toast.makeText(
                    this@ViewportCalibrationActivity,
                    "区域太小，请重新选择",
                    Toast.LENGTH_SHORT,
                ).show()
                invalidate()
                return true
            }

            val adjusted = adjustToGameAspect(raw)
            CapturePreferences.setViewport(
                this@ViewportCalibrationActivity,
                RectF(
                    (adjusted.left / width).coerceIn(0f, 1f),
                    (adjusted.top / height).coerceIn(0f, 1f),
                    (adjusted.right / width).coerceIn(0f, 1f),
                    (adjusted.bottom / height).coerceIn(0f, 1f),
                ),
            )
            Toast.makeText(
                this@ViewportCalibrationActivity,
                "画面区域已按游戏比例校准",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
            return true
        }

        private fun adjustToGameAspect(raw: RectF): RectF {
            val centerX = raw.centerX()
            val centerY = raw.centerY()
            var selectedWidth = raw.width()
            var selectedHeight = raw.height()
            val currentAspect = selectedWidth / selectedHeight.coerceAtLeast(1f)
            if (currentAspect > EXPECTED_GAME_ASPECT) {
                selectedWidth = selectedHeight * EXPECTED_GAME_ASPECT
            } else {
                selectedHeight = selectedWidth / EXPECTED_GAME_ASPECT
            }
            val halfWidth = selectedWidth / 2f
            val halfHeight = selectedHeight / 2f
            val left = (centerX - halfWidth).coerceIn(0f, width - selectedWidth)
            val top = (centerY - halfHeight).coerceIn(0f, height - selectedHeight)
            return RectF(left, top, left + selectedWidth, top + selectedHeight)
        }
    }

    private companion object {
        const val EXPECTED_GAME_ASPECT = 0.45f
        const val MIN_WIDTH_RATIO = 0.35f
        const val MIN_HEIGHT_RATIO = 0.35f
    }
}
