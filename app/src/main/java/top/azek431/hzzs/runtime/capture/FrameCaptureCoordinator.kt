package top.azek431.hzzs.runtime.capture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import android.view.Display
import top.azek431.hzzs.features.service.AutoOperationService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FrameCaptureCoordinator(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hzzs-accessibility-screenshot").apply { isDaemon = true }
    }

    fun capture(): Bitmap? = when (CapturePreferences.mode(context)) {
        CaptureMode.DISABLED -> null
        CaptureMode.ACCESSIBILITY -> accessibilityCapture()
        CaptureMode.MEDIA_PROJECTION -> MediaProjectionCaptureService.latestCopy()
        CaptureMode.ROOT_EXPERIMENTAL -> RootFrameSource.capture()
        CaptureMode.AUTO -> accessibilityCapture() ?: MediaProjectionCaptureService.latestCopy()
    }

    private fun accessibilityCapture(): Bitmap? {
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
                        if (accepting) {
                            captured = copy
                        } else {
                            copy?.takeUnless(Bitmap::isRecycled)?.recycle()
                        }
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            },
        )
        if (!requested) return null

        latch.await(850L, TimeUnit.MILLISECONDS)
        return synchronized(lock) {
            accepting = false
            captured.also { captured = null }
        }
    }

    fun suggestedIntervalMs(): Long = when (CapturePreferences.mode(context)) {
        CaptureMode.ACCESSIBILITY -> 260L
        CaptureMode.AUTO -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && AutoOperationService.isConnected()) 260L else 75L
        CaptureMode.MEDIA_PROJECTION -> 75L
        CaptureMode.ROOT_EXPERIMENTAL -> 650L
        CaptureMode.DISABLED -> 500L
    }

    fun close() {
        executor.shutdownNow()
    }
}
