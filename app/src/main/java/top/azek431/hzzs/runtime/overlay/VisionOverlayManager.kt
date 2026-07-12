package top.azek431.hzzs.runtime.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

object VisionOverlayManager {
    private var wm: WindowManager? = null
    private var view: VisionOverlayView? = null
    @Synchronized fun show(context: Context): Boolean {
        if (view != null) return true
        if (!Settings.canDrawOverlays(context)) return false
        val manager = context.getSystemService(WindowManager::class.java)
        val overlay = VisionOverlayView(context.applicationContext)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        return runCatching { manager.addView(overlay, params); wm = manager; view = overlay; true }.getOrDefault(false)
    }
    @Synchronized fun update(state: VisionOverlayState) { view?.update(state) }
    @Synchronized fun hide() { val current = view ?: return; runCatching { wm?.removeView(current) }; view = null; wm = null }
    fun isShowing() = view != null
}
