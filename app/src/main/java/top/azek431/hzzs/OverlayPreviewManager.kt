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

/**
 * 第一阶段悬浮窗预览管理器。
 *
 * 当前只负责展示、拖动、复制社区入口与关闭。
 * 不接入前台服务、MediaProjection、屏幕采集、HUD 或自动操作。
 */
object OverlayPreviewManager {

    private const val TAG = "HZZS"

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    @Synchronized
    fun isShowing(): Boolean {
        val currentView = overlayView

        if (currentView?.isAttachedToWindow == true) {
            return true
        }

        overlayView = null
        windowManager = null

        return false
    }

    @Synchronized
    fun show(context: Context): Boolean {
        val appContext = context.applicationContext

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(appContext)
        ) {
            Log.w(TAG, "[Overlay] permission is not granted.")
            return false
        }

        if (isShowing()) {
            Log.d(TAG, "[Overlay] show ignored because overlay is already visible.")
            return true
        }

        var candidateView: View? = null
        var candidateWindowManager: WindowManager? = null

        try {
            val manager = appContext.getSystemService(
                Context.WINDOW_SERVICE,
            ) as WindowManager

            candidateWindowManager = manager

            val view = LayoutInflater.from(appContext)
                .inflate(R.layout.view_overlay_preview, null, false)

            candidateView = view

            val closeButton = view.findViewById<View>(R.id.overlayCloseButton)
                ?: throw IllegalStateException("overlayCloseButton is missing.")

            val dragHandle = view.findViewById<View>(R.id.overlayDragHandle)
                ?: throw IllegalStateException("overlayDragHandle is missing.")

            val communityQq = view.findViewById<View>(R.id.overlayCommunityQq)
                ?: throw IllegalStateException("overlayCommunityQq is missing.")

            val communityTelegram = view.findViewById<View>(
                R.id.overlayCommunityTelegram,
            ) ?: throw IllegalStateException(
                "overlayCommunityTelegram is missing.",
            )

            val layoutParams = WindowManager.LayoutParams(
                dp(appContext, 208),
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

                val screenWidth = appContext.resources.displayMetrics.widthPixels

                x = (screenWidth - dp(appContext, 224))
                    .coerceAtLeast(dp(appContext, 8))

                y = dp(appContext, 108)
            }

            closeButton.setOnClickListener {
                hide()
            }

            communityQq.setOnClickListener {
                CommunityLinks.copy(
                    context = appContext,
                    label = appContext.getString(
                        R.string.community_qq_label,
                    ),
                    value = CommunityLinks.HZZS_QQ_GROUP_ID,
                    confirmation = appContext.getString(
                        R.string.overlay_community_qq_copied,
                    ),
                )
            }

            communityTelegram.setOnClickListener {
                CommunityLinks.copy(
                    context = appContext,
                    label = appContext.getString(
                        R.string.community_telegram_label,
                    ),
                    value = CommunityLinks.AZEK_MAIN_TELEGRAM_CHANNEL,
                    confirmation = appContext.getString(
                        R.string.overlay_community_telegram_copied,
                    ),
                )
            }

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
                        layoutParams.x =
                            downWindowX + (event.rawX - downRawX).toInt()

                        layoutParams.y =
                            downWindowY + (event.rawY - downRawY).toInt()

                        try {
                            manager.updateViewLayout(view, layoutParams)
                        } catch (error: IllegalArgumentException) {
                            Log.w(
                                TAG,
                                "[Overlay] overlay was detached while dragging.",
                                error,
                            )
                        }

                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> true

                    else -> false
                }
            }

            manager.addView(view, layoutParams)

            overlayView = view
            windowManager = manager

            Log.i(TAG, "[Overlay] preview overlay is visible.")

            return true
        } catch (error: Exception) {
            Log.e(TAG, "[Overlay] unable to show preview overlay.", error)

            try {
                if (
                    candidateView?.isAttachedToWindow == true &&
                    candidateWindowManager != null
                ) {
                    candidateWindowManager.removeViewImmediate(candidateView)
                }
            } catch (cleanupError: Exception) {
                Log.w(
                    TAG,
                    "[Overlay] cleanup after show failure also failed.",
                    cleanupError,
                )
            }

            overlayView = null
            windowManager = null

            return false
        }
    }

    @Synchronized
    fun hide() {
        val currentView = overlayView ?: return
        val currentWindowManager = windowManager

        overlayView = null
        windowManager = null

        if (currentWindowManager == null) {
            return
        }

        try {
            if (currentView.isAttachedToWindow) {
                currentWindowManager.removeViewImmediate(currentView)
            }

            Log.i(TAG, "[Overlay] preview overlay is hidden.")
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "[Overlay] preview overlay was already removed.", error)
        } catch (error: Exception) {
            Log.w(TAG, "[Overlay] unable to remove preview overlay.", error)
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (
            value * context.resources.displayMetrics.density + 0.5f
        ).toInt()
    }
}
