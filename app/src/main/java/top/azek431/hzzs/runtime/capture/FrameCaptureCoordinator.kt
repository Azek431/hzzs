package top.azek431.hzzs.runtime.capture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import android.os.SystemClock
import android.view.Display
import top.azek431.hzzs.features.service.AutoOperationService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FrameCaptureCoordinator(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hzzs-accessibility-screenshot").apply { isDaemon = true }
    }

    private var accessibilityFailures = 0
    private var accessibilityBlockedUntilMs = 0L

    fun capture(): Bitmap? = when (CapturePreferences.mode(context)) {
        CaptureMode.DISABLED -> null
        CaptureMode.ACCESSIBILITY -> accessibilityCapture(recordFailure = true)
        CaptureMode.MEDIA_PROJECTION -> MediaProjectionCaptureService.latestCopy()
        CaptureMode.ROOT_EXPERIMENTAL -> RootFrameSource.capture()
        CaptureMode.AUTO -> {
            if (SystemClock.uptimeMillis() >= accessibilityBlockedUntilMs) {
                accessibilityCapture(recordFailure = true) ?: MediaProjectionCaptureService.latestCopy()
            } else {
                MediaProjectionCaptureService.latestCopy()
            }
        }
    }

    private fun accessibilityCapture(recordFailure: Boolean): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !AutoOperationService.isConnected()) return null
        val latch = CountDownLatch(1)
        val lock = Any()
        var accepting = true
        var captured: Bitmap? = null
        val requested = AutoOperationService.proxyTakeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hardware = result.hardwareBuffer
                    val copy = try {
                        Bitmap.wrapHardwareBuffer(
                            hardware,
                            result.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB),
                        )?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        hardware.close()
                    }
                    synchronized(lock) {
                        if (accepting) captured = copy else copy?.takeUnless(Bitmap::isRecycled)?.recycle()
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            },
        )
        if (!requested) {
            if (recordFailure) recordAccessibilityFailure()
            return null
        }

        latch.await(ACCESSIBILITY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val result = synchronized(lock) {
            accepting = false
            captured.also { captured = null }
        }
        if (result != null) {
            accessibilityFailures = 0
            accessibilityBlockedUntilMs = 0L
        } else if (recordFailure) {
            recordAccessibilityFailure()
        }
        return result
    }

    private fun recordAccessibilityFailure() {
        accessibilityFailures++
        if (accessibilityFailures >= FAILURE_LIMIT) {
            accessibilityBlockedUntilMs = SystemClock.uptimeMillis() + FAILURE_COOLDOWN_MS
            accessibilityFailures = 0
        }
    }

    fun suggestedIntervalMs(): Long = when (CapturePreferences.mode(context)) {
        CaptureMode.ACCESSIBILITY -> 260L
        CaptureMode.AUTO -> {
            val accessibilityUsable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                AutoOperationService.isConnected() &&
                SystemClock.uptimeMillis() >= accessibilityBlockedUntilMs
            if (accessibilityUsable) 260L else 75L
        }
        CaptureMode.MEDIA_PROJECTION -> 75L
        CaptureMode.ROOT_EXPERIMENTAL -> 650L
        CaptureMode.DISABLED -> 500L
    }

    fun close() {
        executor.shutdownNow()
    }

    private companion object {
        const val ACCESSIBILITY_TIMEOUT_MS = 550L
        const val FAILURE_LIMIT = 3
        const val FAILURE_COOLDOWN_MS = 5_000L
    }
}
