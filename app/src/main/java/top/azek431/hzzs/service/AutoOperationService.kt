// 火崽崽助手（HZZS）无障碍服务 — 自动操作核心。
//
// 工作原理：
// 1. 用户授权无障碍权限后，系统会回调此服务
// 2. 当悬浮窗检测到 ActionPrompt 时，通过静态引用将操作加入队列
// 3. 服务按配置的延迟执行触摸事件注入
//
// 关键设计：
// - 操作队列：避免密集点击，按顺序执行
// - 延迟可配置：0~500ms，模拟人类反应时间
// - 安全开关：悬浮窗 HUD 中可随时暂停/恢复自动操作
//
// 注意：
// - Android 12+ 需要使用 dispatchGesture() 而非已废弃的 injectTouchEvent()
// - 需要在系统设置中手动开启此服务的无障碍权限

package top.azek431.hzzs.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.cos
import kotlin.math.sin

class AutoOperationService : AccessibilityService() {

    companion object {
        private const val TAG = "HZZS-AutoOp"

        // === 操作队列（静态引用，供 HUDRenderer 入队） ===
        private val actionQueue = mutableListOf<QueuedAction>()
        @Volatile private var isPaused = false
        @Volatile private var autoOperationEnabled = false
        @Volatile private var operationDelayMs = 100

        /** 外部调用：将操作加入队列 */
        fun enqueueAction(action: QueuedAction) {
            if (!autoOperationEnabled || isPaused) return
            synchronized(actionQueue) {
                actionQueue.add(action)
            }
        }

        /** 外部调用：清空队列 */
        fun clearQueue() {
            synchronized(actionQueue) {
                actionQueue.clear()
            }
        }

        /** 外部调用：设置开关 */
        fun setEnabled(enabled: Boolean) {
            autoOperationEnabled = enabled
            if (!enabled) clearQueue()
        }

        /** 外部调用：设置暂停 */
        fun setPaused(paused: Boolean) {
            isPaused = paused
        }

        /** 外部调用：设置延迟 */
        fun setDelay(delayMs: Int) {
            operationDelayMs = delayMs.coerceIn(0, 500)
        }

        /** 获取当前队列大小（调试用） */
        fun getQueueSize(): Int = synchronized(actionQueue) { actionQueue.size }
    }

    // ==================== 排队中的操作 ====================

    /** 排队中的操作 */
    data class QueuedAction(
        val type: ActionType,       // JUMP, SLIDE, DOUBLE_JUMP
        val targetX: Float,         // 目标 X 坐标（归一化 0~1）
        val targetY: Float,         // 目标 Y 坐标（归一化 0~1）
        val timestamp: Long,        // 入队时间戳
    ) {
        enum class ActionType {
            JUMP, SLIDE, DOUBLE_JUMP, TAP
        }
    }

    // ==================== 生命周期 ====================

    /** 处理入队操作（在 UI 线程中定时调用） */
    private val handler = Handler(Looper.getMainLooper())
    private val processRunnable = object : Runnable {
        override fun run() {
            processNextAction()
            handler.postDelayed(this, operationDelayMs.toLong())
        }
    }

    override fun onServiceConnected() {
        Log.i(TAG, "[AutoOp] service connected.")
        handler.post(processRunnable)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacks(processRunnable)
        return super.onUnbind(intent)
    }

    // ==================== 操作执行 ====================

    /** 处理队列中的下一个操作 */
    private fun processNextAction() {
        val action = synchronized(actionQueue) {
            if (actionQueue.isEmpty()) return
            actionQueue.removeAt(0)
        } ?: return

        if (isPaused || !autoOperationEnabled) return

        executeAction(action)
        Log.d(TAG, "[AutoOp] executed action: ${action.type} at (${action.targetX}, ${action.targetY})")
    }

    /** 执行具体的触摸操作 */
    private fun executeAction(action: QueuedAction) {
        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val screenH = resources.displayMetrics.heightPixels.toFloat()
        val x = action.targetX * screenW
        val y = action.targetY * screenH

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 使用 dispatchGesture API
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        Path().apply { moveTo(x, y) },
                        0L,
                        50L  // 触摸持续 50ms
                    )
                )
                .build()
            dispatchGesture(gesture, null, null)
        } else {
            // Android 12 以下使用已废弃的 API（降级处理）
            @Suppress("DEPRECATION")
            injectDownEvent(x, y)
            injectUpEvent(x, y)
        }
    }

    /** 注入按下事件（兼容 Android 12 以下） */
    @Suppress("DEPRECATION")
    private fun injectDownEvent(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+：使用 dispatchGesture 替代已废弃的 injectInputEvent
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        Path().apply { moveTo(x, y) },
                        0L,
                        10L
                    )
                )
                .build()
            dispatchGesture(gesture, null, null)
            return
        }

        // Android 12 以下：使用已废弃 API（降级处理）
        val event = MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_DOWN,
            x, y, 1f
        )
        @Suppress("DEPRECATION")
        injectInputEvent(event, ACCESSIBILITY_EVENT_MODE_SYNCHRONOUS)
        event.recycle()
    }

    /** 注入抬起事件（兼容 Android 12 以下） */
    @Suppress("DEPRECATION")
    private fun injectUpEvent(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        Path().apply { moveTo(x, y) },
                        0L,
                        10L
                    )
                )
                .build()
            dispatchGesture(gesture, null, null)
            return
        }

        val event = MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis() + 10,
            MotionEvent.ACTION_UP,
            x, y, 1f
        )
        @Suppress("DEPRECATION")
        injectInputEvent(event, ACCESSIBILITY_EVENT_MODE_SYNCHRONOUS)
        event.recycle()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
