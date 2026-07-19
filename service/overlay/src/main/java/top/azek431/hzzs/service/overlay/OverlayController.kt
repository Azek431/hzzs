package top.azek431.hzzs.service.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import dagger.hilt.android.qualifiers.ApplicationContext
import top.azek431.hzzs.core.model.OverlayConfig
import top.azek431.hzzs.core.model.OverlayTheme
import top.azek431.hzzs.domain.vision.VisionResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverlayController @Inject constructor(@ApplicationContext private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null

    fun show(config: OverlayConfig, result: VisionResult?) {
        if (!Settings.canDrawOverlays(context)) return
        hide()
        val compose = ComposeView(context).apply { setContent { VisionOverlay(config, result) } }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (config.clickThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(compose, params)
        view = compose
    }

    private fun overlayWindowType(): Int = if (Build.VERSION.SDK_INT >= 26) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeViewImmediate(it) } }
        view = null
    }
}

@Composable
private fun VisionOverlay(config: OverlayConfig, result: VisionResult?) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val accent = when (config.theme) {
            OverlayTheme.NEON_GREEN -> Color(0xFF20E89B)
            OverlayTheme.WARNING_ORANGE -> Color(0xFFFF9F1C)
            OverlayTheme.CORAL -> Color(0xFFC73650)
            OverlayTheme.CUSTOM -> Color(config.customColor)
            else -> Color.White
        }
        result?.detections?.forEach { detection ->
            val box = detection.bounds
            drawRect(
                color = if (detection.actionable) accent else accent.copy(alpha = 0.5f),
                topLeft = androidx.compose.ui.geometry.Offset(box.left * size.width, box.top * size.height),
                size = androidx.compose.ui.geometry.Size(box.width * size.width, box.height * size.height),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = config.strokeWidthDp * density),
            )
        }
    }
}
