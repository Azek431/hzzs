package top.azek431.hzzs

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton

/**
 * 第一阶段悬浮窗预览管理器。
 *
 * 当前只负责安全显示、拖动与关闭悬浮窗。
 * 不启动前台服务，不接入 MediaProjection，不执行后台分析。
 */
object OverlayPreviewManager {

    private const val TAG = "HzzsOverlayPreview"

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    @Synchronized
    fun isShowing(): Boolean {
        return overlayView != null
    }

    @Synchronized
    fun show(context: Context): Boolean {
        val appContext = context.applicationContext

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(appContext)
        ) {
            Log.w(TAG, "Overlay permission is not granted.")
            return false
        }

        if (overlayView != null) {
            return true
        }

        val manager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(appContext)
            .inflate(R.layout.view_overlay_preview, null, false)

        val layoutParams = WindowManager.LayoutParams(
            dp(appContext, 188),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = appContext.resources.displayMetrics.widthPixels - dp(appContext, 208)
            y = dp(appContext, 92)
        }

        view.findViewById<ImageButton>(R.id.overlayCloseButton).setOnClickListener {
            hide()
        }

        val dragHandle = view.findViewById<View>(R.id.overlayDragHandle)

        var downRawX = 0f
        var downRawY = 0f
        var downWindowX = 0
        var downWindowY = 0

        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downWindowX = layoutParams.x
                    downWindowY = layoutParams.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = downWindowX + (event.rawX - downRawX).toInt()
                    layoutParams.y = downWindowY + (event.rawY - downRawY).toInt()

                    try {
                        manager.updateViewLayout(view, layoutParams)
                    } catch (error: IllegalArgumentException) {
                        Log.w(TAG, "Overlay was detached while dragging.", error)
                    }

                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> true

                else -> false
            }
        }

        return try {
            manager.addView(view, layoutParams)

            overlayView = view
            windowManager = manager

            true
        } catch (error: SecurityException) {
            Log.e(TAG, "Overlay permission was rejected by the system.", error)
            false
        } catch (error: WindowManager.BadTokenException) {
            Log.e(TAG, "Overlay window token is invalid.", error)
            false
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Overlay view is already attached.", error)
            false
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to create overlay preview.", error)
            false
        }
    }

    @Synchronized
    fun hide() {
        val view = overlayView ?: return
        val manager = windowManager

        overlayView = null
        windowManager = null

        if (manager == null) {
            return
        }

        try {
            manager.removeViewImmediate(view)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Overlay view was already removed.", error)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to remove overlay preview.", error)
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}