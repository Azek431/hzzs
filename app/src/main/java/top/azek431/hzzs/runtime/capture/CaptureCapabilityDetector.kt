package top.azek431.hzzs.runtime.capture

import android.content.Context
import android.os.Build
import top.azek431.hzzs.service.AutoOperationService

data class CaptureCapabilities(
    val androidApi: Int,
    val accessibilityScreenshotSupported: Boolean,
    val accessibilityConnected: Boolean,
    val mediaProjectionSupported: Boolean,
    val mediaProjectionReady: Boolean,
    val rootAvailable: Boolean,
    val recommended: CaptureMode,
)

object CaptureCapabilityDetector {
    fun detect(context: Context): CaptureCapabilities {
        val accessibilitySupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val accessibilityConnected = AutoOperationService.isConnected()
        val mediaReady = MediaProjectionCaptureService.isReady()
        val root = RootFrameSource.isAvailable()
        val recommended = when {
            accessibilitySupported && accessibilityConnected -> CaptureMode.ACCESSIBILITY
            mediaReady -> CaptureMode.MEDIA_PROJECTION
            else -> CaptureMode.MEDIA_PROJECTION
        }
        return CaptureCapabilities(Build.VERSION.SDK_INT, accessibilitySupported, accessibilityConnected, true, mediaReady, root, recommended)
    }
}
