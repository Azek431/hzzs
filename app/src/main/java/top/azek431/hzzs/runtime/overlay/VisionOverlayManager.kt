package top.azek431.hzzs.runtime.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import top.azek431.hzzs.ui.overlay.OverlayPreviewManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object VisionOverlayManager {
    private const val TAG = "HZZS-VisionOverlay"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var wm: WindowManager? = null

    @Volatile
    private var view: VisionOverlayView? = null

    fun show(context: Context): Boolean = runOnMain {
        synchronized(lock) {
            if (view?.isAttachedToWindow == true) return@runOnMain true
            val app = context.applicationContext
            if (!Settings.canDrawOverlays(app)) return@runOnMain false

            // 旧预览与新版实时 HUD 只能存在一套全屏 Surface。
            OverlayPreviewManager.hide("vision-runtime")

            val manager = app.getSystemService(WindowManager::class.java)
            val overlay = VisionOverlayView(app)
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            try {
                manager.addView(overlay, params)
                wm = manager
                view = overlay
                true
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to add vision overlay", error)
                runCatching {
                    if (overlay.isAttachedToWindow) manager.removeViewImmediate(overlay)
                }
                view = null
                wm = null
                false
            }
        }
    }

    fun update(state: VisionOverlayState) {
        view?.update(state)
    }

    fun hide() {
        runOnMain {
            synchronized(lock) {
                val current = view
                val manager = wm
                if (current != null && manager != null) {
                    runCatching {
                        if (current.isAttachedToWindow) manager.removeViewImmediate(current)
                    }.onFailure { Log.w(TAG, "Unable to remove vision overlay", it) }
                }
                view = null
                wm = null
                true
            }
        }
    }

    fun isShowing(): Boolean = view?.isAttachedToWindow == true

    private fun runOnMain(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()

        val result = AtomicReference(false)
        val failure = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result.set(block())
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(1_500L, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Timed out waiting for main-thread overlay operation")
            return false
        }
        failure.get()?.let { Log.e(TAG, "Main-thread overlay operation failed", it) }
        return result.get()
    }
}
