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
import android.widget.TextView
import android.widget.Toast

/**
 * 第一阶段悬浮窗预览管理器。
 *
 * 当前负责：
 * - 显示、拖动、关闭
 * - 社区链接跳转
 * - 分析启动入口的 UI 状态
 *
 * 当前不负责：
 * - 屏幕采集
 * - MediaProjection
 * - 帧分析
 * - 障碍识别
 * - 自动操作
 */
object OverlayPreviewManager {

    private const val TAG = "HZZS"

    private enum class AnalysisUiState {
        IDLE,
        PREPARING,
    }

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var analysisUiState = AnalysisUiState.IDLE

    @Synchronized
    fun isShowing(): Boolean {
        val currentView = overlayView

        if (currentView?.isAttachedToWindow == true) {
            return true
        }

        overlayView = null
        windowManager = null
        analysisUiState = AnalysisUiState.IDLE

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
            analysisUiState = AnalysisUiState.IDLE

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

            val statusText = view.findViewById<TextView>(R.id.overlayStatusText)
                ?: throw IllegalStateException("overlayStatusText is missing.")

            val startAnalysisButton = view.findViewById<TextView>(
                R.id.overlayStartAnalysis,
            ) ?: throw IllegalStateException(
                "overlayStartAnalysis is missing.",
            )

            val communityQq = view.findViewById<View>(R.id.overlayCommunityQq)
                ?: throw IllegalStateException("overlayCommunityQq is missing.")

            val communityTelegram = view.findViewById<View>(
                R.id.overlayCommunityTelegram,
            ) ?: throw IllegalStateException(
                "overlayCommunityTelegram is missing.",
            )

            val overlayWidth = dp(appContext, 192)
            val screenWidth = appContext.resources.displayMetrics.widthPixels
            val maxX = (screenWidth - overlayWidth).coerceAtLeast(0)

            val layoutParams = WindowManager.LayoutParams(
                overlayWidth,
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
                x = (screenWidth - dp(appContext, 208))
                    .coerceIn(0, maxX)
                y = dp(appContext, 108)
            }

            closeButton.setOnClickListener {
                hide()
            }

            startAnalysisButton.setOnClickListener {
                if (analysisUiState != AnalysisUiState.IDLE) {
                    return@setOnClickListener
                }

                analysisUiState = AnalysisUiState.PREPARING

                statusText.setText(R.string.overlay_analysis_preparing)
                startAnalysisButton.setText(R.string.overlay_analysis_waiting)
                startAnalysisButton.isEnabled = false
                startAnalysisButton.alpha = 0.72f

                Log.i(TAG, "[Analysis] analysis preparation requested.")

                Toast.makeText(
                    appContext,
                    R.string.overlay_analysis_placeholder,
                    Toast.LENGTH_SHORT,
                ).show()
            }

            communityQq.setOnClickListener {
                hide()

                CommunityLinks.openLink(
                    context = appContext,
                    label = appContext.getString(
                        R.string.community_qq_label,
                    ),
                    url = CommunityLinks.HZZS_QQ_GROUP_URL,
                    fallbackMessage = appContext.getString(
                        R.string.community_open_fallback,
                    ),
                )
            }

            communityTelegram.setOnClickListener {
                hide()

                CommunityLinks.openLink(
                    context = appContext,
                    label = appContext.getString(
                        R.string.community_telegram_label,
                    ),
                    url = CommunityLinks.AZEK_MAIN_TELEGRAM_URL,
                    fallbackMessage = appContext.getString(
                        R.string.community_open_fallback,
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
                        layoutParams.x = (
                            downWindowX + (event.rawX - downRawX).toInt()
                        ).coerceIn(0, maxX)

                        layoutParams.y = (
                            downWindowY + (event.rawY - downRawY).toInt()
                        ).coerceAtLeast(0)

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
            analysisUiState = AnalysisUiState.IDLE

            return false
        }
    }

    @Synchronized
    fun hide() {
        val currentView = overlayView ?: return
        val currentWindowManager = windowManager

        overlayView = null
        windowManager = null
        analysisUiState = AnalysisUiState.IDLE

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
