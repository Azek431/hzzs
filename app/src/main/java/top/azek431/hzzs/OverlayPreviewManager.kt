package top.azek431.hzzs

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * HZZS 第一阶段悬浮窗管理器。
 *
 * 当前负责：
 * - 显示、拖动、关闭
 * - 数据分析准备入口
 * - QQ 群与 Telegram 主频道跳转
 *
 * 当前不接入：
 * - MediaProjection
 * - 屏幕采集
 * - 帧分析
 * - 障碍识别
 * - 自动操作
 */
object OverlayPreviewManager {

    private const val TAG = "HZZS"
    private const val PREFS_NAME = "hzzs_overlay_prefs"
    private const val KEY_ALPHA = "overlay_alpha"
    private const val KEY_SCALE = "overlay_scale"
    private const val KEY_RADIUS = "overlay_radius"

    private enum class AnalysisUiState {
        IDLE,
        EXECUTING,
    }

    private data class OverlaySession(
        val rootView: View,
        val manager: WindowManager,
    )

    private var activeSession: OverlaySession? = null
    private var analysisUiState = AnalysisUiState.IDLE

    /**
     * 只以内部会话是否存在判断状态。
     *
     * 不使用 View.isAttachedToWindow：
     * 某些设备在 addView() 后短时间内仍可能返回 false，
     * 但系统 Overlay 实际已经显示。若此时清空引用，将无法关闭窗口。
     */
    @Synchronized
    fun isShowing(): Boolean {
        return activeSession != null
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

        if (activeSession != null) {
            Log.d(TAG, "[Overlay] show ignored because a session is already active.")
            return true
        }

        var candidateView: View? = null
        var candidateManager: WindowManager? = null

        try {
            analysisUiState = AnalysisUiState.IDLE

            val manager = appContext.getSystemService(
                Context.WINDOW_SERVICE,
            ) as WindowManager

            candidateManager = manager

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

            // 调节控件（可选，不存在时跳过）
            val _ = view.findViewById<SeekBar>(R.id.overlayAlphaSlider)
            val _2 = view.findViewById<TextView>(R.id.overlayAlphaValue)
            val _3 = view.findViewById<SeekBar>(R.id.overlayScaleSlider)
            val _4 = view.findViewById<TextView>(R.id.overlayScaleValue)
            val _5 = view.findViewById<SeekBar>(R.id.overlayRadiusSlider)
            val _6 = view.findViewById<TextView>(R.id.overlayRadiusValue)

            val overlayWidth = dp(appContext, 228)
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
                x = (screenWidth - dp(appContext, 244))
                    .coerceIn(0, maxX)
                y = dp(appContext, 108)
            }

            closeButton.setOnClickListener {
                Log.i(TAG, "[Overlay] close requested.")
                hide("close-button")
            }

            startAnalysisButton.setOnClickListener {
                when (analysisUiState) {
                    AnalysisUiState.IDLE -> {
                        analysisUiState = AnalysisUiState.EXECUTING

                        statusText.setText(
                            R.string.overlay_analysis_running,
                        )

                        startAnalysisButton.setText(
                            R.string.overlay_analysis_stop,
                        )

                        startAnalysisButton.isEnabled = true
                        startAnalysisButton.alpha = 1f

                        Log.i(
                            TAG,
                            "[Analysis] execution started.",
                        )

                        Toast.makeText(
                            appContext,
                            R.string.overlay_analysis_started,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }

                    AnalysisUiState.EXECUTING -> {
                        analysisUiState = AnalysisUiState.IDLE

                        statusText.setText(
                            R.string.overlay_preview_status,
                        )

                        startAnalysisButton.setText(
                            R.string.overlay_analysis_start,
                        )

                        startAnalysisButton.isEnabled = true
                        startAnalysisButton.alpha = 1f

                        Log.i(
                            TAG,
                            "[Analysis] execution stopped; overlay remains visible.",
                        )

                        Toast.makeText(
                            appContext,
                            R.string.overlay_analysis_stopped,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }

            communityQq.setOnClickListener {
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

            // ---- 调节控件 ----
            setupAdjustmentSliders(appContext, view)

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
                                "[Overlay] detached while dragging.",
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

            // 恢复上次保存的调节参数
            restoreAdjustments(appContext, view, layoutParams)

            manager.addView(view, layoutParams)

            activeSession = OverlaySession(
                rootView = view,
                manager = manager,
            )

            Log.i(TAG, "[Overlay] session created and window is visible.")

            return true
        } catch (error: Exception) {
            Log.e(TAG, "[Overlay] unable to show preview overlay.", error)

            try {
                if (
                    candidateView != null &&
                    candidateManager != null
                ) {
                    candidateManager.removeViewImmediate(candidateView)
                }
            } catch (cleanupError: Exception) {
                Log.w(
                    TAG,
                    "[Overlay] cleanup after show failure also failed.",
                    cleanupError,
                )
            }

            activeSession = null
            analysisUiState = AnalysisUiState.IDLE

            return false
        }
    }

    /**
     * 将 SharedPreferences 中保存的调节参数应用到悬浮窗 View 上。
     */
    private fun restoreAdjustments(
        context: Context,
        view: View,
        layoutParams: WindowManager.LayoutParams,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alpha = prefs.getFloat(KEY_ALPHA, 1.0f)
        val scale = prefs.getFloat(KEY_SCALE, 1.0f)
        val radiusDp = prefs.getInt(KEY_RADIUS, 20)

        // 透明度
        view.alpha = alpha

        // 圆角（dp → px）
        val radiusPx = (radiusDp * context.resources.displayMetrics.density).roundToInt()
        view.background?.setCornerRadii(floatArrayOf(
            radiusPx.toFloat(), radiusPx.toFloat(),
            radiusPx.toFloat(), radiusPx.toFloat(),
            radiusPx.toFloat(), radiusPx.toFloat(),
            radiusPx.toFloat(), radiusPx.toFloat(),
        ))

        // 尺寸缩放（通过 scaleX/scaleY）
        view.scaleX = scale
        view.scaleY = scale

        // SeekBar 进度回写
        val alphaSlider = view.findViewById<SeekBar>(R.id.overlayAlphaSlider)
        val alphaValue = view.findViewById<TextView>(R.id.overlayAlphaValue)
        alphaSlider?.progress = (alpha * 100).toInt()
        alphaValue?.text = "${alphaSlider.progress}%"

        val scaleSlider = view.findViewById<SeekBar>(R.id.overlayScaleSlider)
        val scaleValue = view.findViewById<TextView>(R.id.overlayScaleValue)
        scaleSlider?.progress = ((scale - 0.6f) / 0.6f * 100).toInt()
        scaleValue?.text = "${(scale * 100).roundToInt()}%"

        val radiusSlider = view.findViewById<SeekBar>(R.id.overlayRadiusSlider)
        val radiusValue = view.findViewById<TextView>(R.id.overlayRadiusValue)
        radiusSlider?.progress = radiusDp - 4
        radiusValue?.text = "$radiusDp dp"
    }

    @Synchronized
    fun hide(reason: String = "manual"): Boolean {
        val session = activeSession

        if (session == null) {
            Log.d(TAG, "[Overlay] hide ignored. reason=$reason, no session.")
            return false
        }

        /*
         * 先清空会话，阻止多次快速点击导致重复 removeView。
         * removeViewImmediate 即使窗口被系统提前移除，也只会抛异常并被捕获。
         */
        activeSession = null
        analysisUiState = AnalysisUiState.IDLE

        return try {
            session.manager.removeViewImmediate(session.rootView)

            Log.i(TAG, "[Overlay] window removed. reason=$reason")
            true
        } catch (error: IllegalArgumentException) {
            Log.w(
                TAG,
                "[Overlay] window was already detached. reason=$reason",
                error,
            )
            false
        } catch (error: Exception) {
            Log.e(
                TAG,
                "[Overlay] unable to remove window. reason=$reason",
                error,
            )
            false
        }
    }

    /**
     * 绑定悬浮窗上的三个调节滑块：透明度、尺寸缩放、圆角半径。
     */
    private fun setupAdjustmentSliders(
        context: Context,
        view: View,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // --- 透明度滑块 ---
        val alphaSlider = view.findViewById<SeekBar>(R.id.overlayAlphaSlider)
        val alphaValue = view.findViewById<TextView>(R.id.overlayAlphaValue)
        alphaSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val alpha = progress / 100f
                view.alpha = alpha
                alphaValue?.text = "$progress%"
                prefs.edit().putFloat(KEY_ALPHA, alpha).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // --- 尺寸缩放滑块（0.6 → 1.6，映射到 0~100） ---
        val scaleSlider = view.findViewById<SeekBar>(R.id.overlayScaleSlider)
        val scaleValue = view.findViewById<TextView>(R.id.overlayScaleValue)
        scaleSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val scale = 0.6f + (progress / 100f) * 1.0f
                view.scaleX = scale
                view.scaleY = scale
                scaleValue?.text = "${(scale * 100).roundToInt()}%"
                prefs.edit().putFloat(KEY_SCALE, scale).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // --- 圆角滑块（4~40 dp，映射到 0~100） ---
        val radiusSlider = view.findViewById<SeekBar>(R.id.overlayRadiusSlider)
        val radiusValue = view.findViewById<TextView>(R.id.overlayRadiusValue)
        radiusSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val radiusDp = 4 + progress
                val radiusPx = (radiusDp * context.resources.displayMetrics.density).roundToInt()
                view.background?.setCornerRadii(floatArrayOf(
                    radiusPx.toFloat(), radiusPx.toFloat(),
                    radiusPx.toFloat(), radiusPx.toFloat(),
                    radiusPx.toFloat(), radiusPx.toFloat(),
                    radiusPx.toFloat(), radiusPx.toFloat(),
                ))
                radiusValue?.text = "$radiusDp dp"
                prefs.edit().putInt(KEY_RADIUS, radiusDp).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun dp(context: Context, value: Int): Int {
        return (
            value * context.resources.displayMetrics.density + 0.5f
        ).toInt()
    }
}
