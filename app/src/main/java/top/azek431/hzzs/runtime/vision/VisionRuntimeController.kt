package top.azek431.hzzs.runtime.vision

import android.content.Context
import android.os.SystemClock
import android.util.Log
import top.azek431.hzzs.features.service.AutoOperationService
import top.azek431.hzzs.features.service.RuntimeActionQueue
import top.azek431.hzzs.runtime.capture.CapturePreferences
import top.azek431.hzzs.runtime.capture.FrameCaptureCoordinator
import top.azek431.hzzs.runtime.overlay.VisionOverlayManager
import top.azek431.hzzs.runtime.overlay.VisionOverlayState
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VisionRuntimeController(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val capture = FrameCaptureCoordinator(app)
    private val tracker = VisionTracker()
    private val planner = VisionActionPlanner(tracker)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "hzzs-vision-v2").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    private var future: ScheduledFuture<*>? = null
    private var lastFpsAt = SystemClock.uptimeMillis()
    private var frameCount = 0
    private var fps = 0f

    fun start() {
        if (!running.compareAndSet(false, true)) return
        synchronizeRuntimePreferences()
        schedule(0L)
    }

    private fun schedule(delayMs: Long) {
        if (!running.get() || executor.isShutdown) return
        future = executor.schedule(::cycle, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    private fun synchronizeRuntimePreferences() {
        val actionReady = CapturePreferences.autoAction(app) &&
            AutoOperationService.isConnected() &&
            AutoOperationService.isActionTargetAllowed()
        RuntimeActionQueue.setEnabled(actionReady)

        if (CapturePreferences.draw(app)) {
            VisionOverlayManager.show(app)
        } else {
            VisionOverlayManager.hide()
        }
    }

    private fun cycle() {
        if (!running.get()) return
        val started = SystemClock.elapsedRealtimeNanos()
        var nextDelay = capture.suggestedIntervalMs()

        try {
            synchronizeRuntimePreferences()
            val bitmap = capture.capture()
            if (bitmap == null) {
                VisionOverlayManager.update(
                    VisionOverlayState(
                        captureMode = CapturePreferences.mode(app).name,
                        fps = fps,
                        status = "WAITING_CAPTURE_PERMISSION",
                        detailed = CapturePreferences.detailed(app),
                    ),
                )
                schedule(nextDelay)
                return
            }

            val normalized = try {
                FrameNormalizer.normalize(app, bitmap)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }

            val result = HzzsVisionBridge.analyze(normalized)?.let(tracker::update)
            if (result == null) {
                VisionOverlayManager.update(
                    VisionOverlayState(
                        frame = normalized.mapping,
                        captureMode = CapturePreferences.mode(app).name,
                        fps = fps,
                        status = "NATIVE_RESULT_UNAVAILABLE",
                        detailed = CapturePreferences.detailed(app),
                    ),
                )
                schedule(nextDelay)
                return
            }

            val autoRequested = CapturePreferences.autoAction(app)
            val accessibilityReady = AutoOperationService.isConnected()
            val targetAllowed = AutoOperationService.isActionTargetAllowed()
            val actionReady = autoRequested && accessibilityReady && targetAllowed
            val actions = if (actionReady) planner.plan(result) else emptyList()
            val accepted = if (actions.isNotEmpty()) RuntimeActionQueue.enqueueAll(actions) else 0
            if (accepted == actions.size && accepted > 0) result.primary?.let(planner::commit)

            val status = when {
                accepted > 0 -> "TRIGGERED"
                result.primary == null -> "SEARCHING"
                !autoRequested -> "AUTO_ACTION_OFF"
                !accessibilityReady -> "WAITING_ACCESSIBILITY"
                !targetAllowed -> "WAITING_ALLOWED_APP"
                result.primary.distanceP <= 1.50f -> "DANGER_WAITING_QUEUE"
                else -> "APPROACHING"
            }
            val actionText = if (accepted > 0) {
                actions.take(accepted).joinToString("+") { it.type.name }
            } else {
                "WAIT"
            }

            updateFps()
            VisionOverlayManager.update(
                VisionOverlayState(
                    result = result,
                    frame = normalized.mapping,
                    captureMode = CapturePreferences.mode(app).name,
                    fps = fps,
                    totalCostMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000f,
                    status = status,
                    lastAction = actionText,
                    detailed = CapturePreferences.detailed(app),
                ),
            )
        } catch (throwable: Throwable) {
            Log.e(TAG, "vision cycle failed", throwable)
            VisionOverlayManager.update(
                VisionOverlayState(
                    captureMode = CapturePreferences.mode(app).name,
                    fps = fps,
                    status = "ERROR:${throwable.javaClass.simpleName}",
                    detailed = CapturePreferences.detailed(app),
                ),
            )
            nextDelay = 180L
        }

        schedule(nextDelay)
    }

    private fun updateFps() {
        frameCount++
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastFpsAt
        if (elapsed >= 1_000L) {
            fps = frameCount * 1_000f / elapsed
            frameCount = 0
            lastFpsAt = now
        }
    }

    fun stop() {
        running.set(false)
        future?.cancel(true)
        future = null
        RuntimeActionQueue.setEnabled(false)
        tracker.reset()
        VisionOverlayManager.hide()
    }

    override fun close() {
        stop()
        capture.close()
        executor.shutdownNow()
    }

    private companion object {
        const val TAG = "HZZS-Runtime"
    }
}
