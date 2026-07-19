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
    private var lastAlgorithm: VisionAlgorithm? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        schedule(0L)
    }

    private fun schedule(delayMs: Long) {
        if (!running.get() || executor.isShutdown) return
        future = executor.schedule(::cycle, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    private fun cycle() {
        if (!running.get()) return
        val started = SystemClock.elapsedRealtimeNanos()
        var nextDelay = capture.suggestedIntervalMs()
        try {
            val algorithm = CapturePreferences.algorithm(app)
            if (algorithm != lastAlgorithm) {
                RuntimeActionQueue.clear()
                tracker.reset()
                lastAlgorithm = algorithm
            }

            // 先截图，后更新悬浮层；悬浮窗口同时使用 FLAG_SECURE，避免 HUD 被捕获回算法。
            val bitmap = capture.capture()
            synchronizeRuntimePreferences(algorithm)
            if (bitmap == null) {
                VisionOverlayManager.update(
                    VisionOverlayState(
                        captureMode = CapturePreferences.mode(app).name,
                        algorithm = algorithm,
                        fps = fps,
                        status = "WAITING_CAPTURE_PERMISSION",
                        detailed = CapturePreferences.detailed(app),
                    ),
                )
                return
            }

            val normalized = try {
                FrameNormalizer.normalize(app, bitmap, maxWorkWidth = algorithm.workWidth)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }

            val nativeResult = HzzsVisionBridge.analyze(normalized, algorithm = algorithm)
            if (nativeResult == null) {
                RuntimeActionQueue.clear()
                tracker.reset()
                VisionOverlayManager.update(
                    VisionOverlayState(
                        frame = normalized.mapping,
                        captureMode = CapturePreferences.mode(app).name,
                        algorithm = algorithm,
                        fps = fps,
                        status = "NATIVE_RESULT_UNAVAILABLE",
                        detailed = CapturePreferences.detailed(app),
                    ),
                )
                return
            }

            val result = if (nativeResult.sceneState == VisionSceneState.RUNNING) {
                tracker.update(nativeResult)
            } else {
                RuntimeActionQueue.clear()
                tracker.reset()
                nativeResult.copy(detections = emptyList(), primary = null)
            }

            val autoRequested = CapturePreferences.autoAction(app)
            val algorithmAllowsAction = CapturePreferences.actionAllowedByAlgorithm(app, algorithm)
            val accessibilityReady = AutoOperationService.isConnected()
            val targetAllowed = AutoOperationService.isActionTargetAllowed()
            val actionReady = autoRequested && algorithmAllowsAction && accessibilityReady && targetAllowed
            RuntimeActionQueue.setEnabled(actionReady)

            val actions = if (actionReady) {
                planner.plan(
                    result,
                    bambooExperimentalEnabled = CapturePreferences.bambooExperimentalAutoAction(app),
                )
            } else {
                emptyList()
            }
            val accepted = if (actions.isNotEmpty()) RuntimeActionQueue.enqueueAll(actions) else 0
            if (accepted == actions.size && accepted > 0) result.primary?.let(planner::commit)

            val status = when {
                result.sceneState != VisionSceneState.RUNNING -> "UNSAFE_SCENE"
                result.playerConfidence < MIN_PLAYER_CONFIDENCE -> "PLAYER_NOT_CONFIDENT"
                accepted > 0 -> "TRIGGERED"
                result.primary == null -> "SEARCHING"
                !autoRequested -> "AUTO_ACTION_OFF"
                !algorithmAllowsAction -> "ALGORITHM_ACTION_LOCKED"
                !accessibilityReady -> "WAITING_ACCESSIBILITY"
                !targetAllowed -> "WAITING_ALLOWED_APP"
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
                    algorithm = algorithm,
                    fps = fps,
                    totalCostMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000f,
                    status = status,
                    lastAction = actionText,
                    detailed = CapturePreferences.detailed(app),
                ),
            )
        } catch (throwable: Throwable) {
            Log.e(TAG, "vision cycle failed", throwable)
            RuntimeActionQueue.clear()
            tracker.reset()
            VisionOverlayManager.update(
                VisionOverlayState(
                    captureMode = CapturePreferences.mode(app).name,
                    algorithm = CapturePreferences.algorithm(app),
                    fps = fps,
                    status = "ERROR:${throwable.javaClass.simpleName}",
                    detailed = CapturePreferences.detailed(app),
                ),
            )
            nextDelay = 180L
        } finally {
            schedule(nextDelay)
        }
    }

    private fun synchronizeRuntimePreferences(algorithm: VisionAlgorithm) {
        val actionReady = CapturePreferences.autoAction(app) &&
            CapturePreferences.actionAllowedByAlgorithm(app, algorithm) &&
            AutoOperationService.isConnected() &&
            AutoOperationService.isActionTargetAllowed()
        RuntimeActionQueue.setEnabled(actionReady)

        if (CapturePreferences.draw(app)) {
            VisionOverlayManager.show(app)
        } else {
            VisionOverlayManager.hide()
        }
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
        RuntimeActionQueue.clear()
        RuntimeActionQueue.setEnabled(false)
        tracker.reset()
        lastAlgorithm = null
        VisionOverlayManager.hide()
    }

    override fun close() {
        stop()
        capture.close()
        executor.shutdownNow()
    }

    private companion object {
        const val TAG = "HZZS-Runtime"
        const val MIN_PLAYER_CONFIDENCE = 0.45f
    }
}
