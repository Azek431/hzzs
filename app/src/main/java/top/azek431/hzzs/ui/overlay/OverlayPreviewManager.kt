// 火崽崽助手（HZZS）悬浮窗管理器。
//
// 单例对象，负责管理系统级悬浮窗（WindowManager Overlay）的完整生命周期：
// 创建、显示、拖动、关闭、参数持久化和恢复。
//
// 当前负责的功能：
// - 显示、拖动、关闭悬浮窗预览面板
// - 数据分析准备入口（开始/停止按钮）
// - QQ 群与 Telegram 主频道跳转
// - 悬浮窗透明度调节滑块
// - 悬浮窗位置、透明度参数的持久化存储与恢复
// - 自动操作开关与延迟调节
//
// 预留接口：
// - MediaProjection（屏幕采集）
// - 真实帧分析
// - 障碍识别

package top.azek431.hzzs.ui.overlay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import androidx.appcompat.widget.SwitchCompat
import kotlin.math.roundToInt
import top.azek431.hzzs.R
import top.azek431.hzzs.model.RectF
import top.azek431.hzzs.service.OverlayNotificationService
import top.azek431.hzzs.CommunityLinks

object OverlayPreviewManager {

    /** 日志标签 */
    private const val TAG = "HZZS"

    /** SharedPreferences 文件名 */
    private const val PREFS_NAME = "hzzs_overlay_prefs"

    /** 透明度参数键 */
    private const val KEY_ALPHA = "overlay_alpha"

    /** 圆角半径参数键 */
    private const val KEY_RADIUS = "overlay_radius"

    // ==================== HUD 渲染器 ====================

    /** HUD 渲染器实例 */
    private var hudRenderer: OverlayHUDRenderer? = null

    // ==================== 悬浮窗会话 ====================

    /** 当前活跃的悬浮窗会话 */
    private data class OverlaySession(
        val rootView: View,
        val manager: WindowManager,
    )

    private var activeSession: OverlaySession? = null

    // ==================== 数据分析状态 ====================

    private enum class AnalysisUiState {
        IDLE,
        EXECUTING,
    }

    @Volatile
    private var analysisUiState = AnalysisUiState.IDLE

    /** 检查当前是否有悬浮窗正在显示 */
    @Synchronized
    fun isShowing(): Boolean = activeSession != null

    // ==================== 显示/隐藏 ====================

    /**
     * 创建并显示悬浮窗预览面板。
     *
     * @param context 上下文
     * @return true 如果成功显示，false 如果权限不足或发生异常
     */
    @Synchronized
    fun show(context: Context): Boolean {
        val appContext = context.applicationContext

        // 权限检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(appContext)
        ) {
            Log.w(TAG, "[Overlay] permission is not granted.")
            return false
        }

        // 去重检查
        if (activeSession != null) {
            Log.d(TAG, "[Overlay] show ignored because a session is already active.")
            return true
        }

        var candidateView: View? = null
        var candidateManager: WindowManager? = null

        try {
            analysisUiState = AnalysisUiState.IDLE

            val manager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            candidateManager = manager

            val view = LayoutInflater.from(appContext)
                .inflate(R.layout.view_overlay_preview, null, false)
            candidateView = view

            // 查找必需子控件
            val closeButton = view.findViewById<View>(R.id.overlayCloseButton)
                ?: throw IllegalStateException("overlayCloseButton is missing.")
            val contentPanel = view.findViewById<View>(R.id.overlayContentPanel)
                ?: throw IllegalStateException("overlayContentPanel is missing.")
            val statusText = view.findViewById<TextView>(R.id.overlayStatusText)
                ?: throw IllegalStateException("overlayStatusText is missing.")
            val startAnalysisButton = view.findViewById<TextView>(R.id.overlayStartAnalysis)
                ?: throw IllegalStateException("overlayStartAnalysis is missing.")
            val communityQq = view.findViewById<View>(R.id.overlayCommunityQq)
                ?: throw IllegalStateException("overlayCommunityQq is missing.")
            val communityTelegram = view.findViewById<View>(R.id.overlayCommunityTelegram)
                ?: throw IllegalStateException("overlayCommunityTelegram is missing.")
            val resizeHandle = view.findViewById<View>(R.id.overlayResizeHandle)

            // 计算悬浮窗尺寸和位置
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
                x = (screenWidth - dp(appContext, 244)).coerceIn(0, maxX)
                y = dp(appContext, 108)
            }

            // 绑定开始/停止分析按钮
            startAnalysisButton.setOnClickListener {
                when (analysisUiState) {
                    AnalysisUiState.IDLE -> {
                        analysisUiState = AnalysisUiState.EXECUTING
                        statusText.setText(R.string.overlay_analysis_running)
                        startAnalysisButton.setText(R.string.overlay_analysis_stop)
                        startAnalysisButton.isEnabled = true
                        startAnalysisButton.alpha = 1f
                        Log.i(TAG, "[Analysis] execution started.")
                        Toast.makeText(appContext, R.string.overlay_analysis_started, Toast.LENGTH_SHORT).show()
                        hudRenderer?.start()
                    }
                    AnalysisUiState.EXECUTING -> {
                        analysisUiState = AnalysisUiState.IDLE
                        statusText.setText(R.string.overlay_preview_status)
                        startAnalysisButton.setText(R.string.overlay_analysis_start)
                        startAnalysisButton.isEnabled = true
                        startAnalysisButton.alpha = 1f
                        Log.i(TAG, "[Analysis] execution stopped; overlay remains visible.")
                        Toast.makeText(appContext, R.string.overlay_analysis_stopped, Toast.LENGTH_SHORT).show()
                        hudRenderer?.stop()
                    }
                }
            }

            // 绑定社区链接
            val fallbackMsg = appContext.getString(R.string.community_open_fallback)
            val overlayLinkViews = mapOf<Int, Int>(
                R.id.overlayCommunityQq to R.string.community_qq_label,
                R.id.overlayCommunityTelegram to R.string.community_telegram_label,
            )
            for (entry in CommunityLinks.entries) {
                val viewId = overlayLinkViews.entries.find { (_, labelRes) ->
                    labelRes == entry.labelRes
                }?.key
                if (viewId != null) {
                    view.findViewById<View>(viewId)?.setOnClickListener {
                        CommunityLinks.openLink(appContext, appContext.getString(entry.labelRes), entry.url, fallbackMsg)
                    }
                }
            }

            // 绑定透明度滑块
            setupAdjustmentSliders(appContext, view)

            // === 绑定自动操作控件 ===
            bindAutoOperationControls(appContext, view)

            // ---- 拖动与缩放逻辑 ----
            var downRawX = 0f
            var downRawY = 0f
            var downWindowX = 0
            var downWindowY = 0
            var initialWidth = overlayWidth
            var isScaling = false

            closeButton.setOnClickListener {
                Log.i(TAG, "[Overlay] close requested.")
                hide("close-button")
            }

            contentPanel.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val clickableIds = setOf(
                            R.id.overlayCloseButton,
                            R.id.overlayStartAnalysis,
                            R.id.overlayCommunityQq,
                            R.id.overlayCommunityTelegram,
                            R.id.overlayAlphaSlider,
                            R.id.overlayAutoOpSwitch,
                        )

                        val x = event.x.toInt()
                        val y = event.y.toInt()
                        var isClickableChild = false

                        for (clickableId in clickableIds) {
                            val child = v.findViewById<View>(clickableId) ?: continue
                            if (child.isClickable && child.visibility == View.VISIBLE) {
                                if (x >= child.left && x <= child.right &&
                                    y >= child.top && y <= child.bottom) {
                                    isClickableChild = true
                                    break
                                }
                            }
                        }

                        if (isClickableChild) {
                            false
                        } else {
                            downRawX = event.rawX
                            downRawY = event.rawY
                            downWindowX = layoutParams.x
                            downWindowY = layoutParams.y
                            initialWidth = layoutParams.width
                            isScaling = false
                            true
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()

                        val dragThresholdPx = dp(appContext, 5)
                        if (!isScaling && (Math.abs(dx) > dragThresholdPx || Math.abs(dy) > dragThresholdPx)) {
                            val panelRight = v.width
                            val panelBottom = v.height
                            val isInResizeCorner = (event.rawX > panelRight - dp(appContext, 48)) &&
                                (event.rawY > panelBottom - dp(appContext, 48))

                            if (isInResizeCorner && resizeHandle != null) {
                                isScaling = true
                            }
                        }

                        if (isScaling && resizeHandle != null) {
                            val newWidth = (initialWidth + dx).coerceIn(
                                (initialWidth * 0.5).toInt(),
                                (initialWidth * 1.5).toInt()
                            )
                            layoutParams.width = newWidth
                        } else {
                            layoutParams.x = (downWindowX + dx).coerceIn(0, maxX)
                            layoutParams.y = (downWindowY + dy).coerceAtLeast(0)
                        }

                        try {
                            manager.updateViewLayout(view, layoutParams)
                        } catch (error: IllegalArgumentException) {
                            Log.w(TAG, "[Overlay] detached while dragging.", error)
                        }

                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isScaling = false
                        true
                    }

                    else -> false
                }
            }

            // 恢复上次保存的参数
            restoreAdjustments(appContext, view)

            // 初始化 HUD 渲染器
            hudRenderer = OverlayHUDRenderer(appContext).apply {
                bindViews(view)
            }

            // 添加到 WindowManager
            try {
                manager.addView(view, layoutParams)
            } catch (iae: IllegalStateException) {
                Log.w(TAG, "[Overlay] view already attached, reusing session.", iae)
                activeSession = OverlaySession(view, manager)
                return true
            }

            activeSession = OverlaySession(view, manager)
            OverlayNotificationService.start(appContext)

            Log.i(TAG, "[Overlay] session created and window is visible.")
            return true
        } catch (error: Exception) {
            Log.e(TAG, "[Overlay] unable to show preview overlay.", error)
            try {
                if (candidateView != null && candidateManager != null) {
                    candidateManager.removeView(candidateView)
                }
            } catch (cleanupError: Exception) {
                Log.w(TAG, "[Overlay] cleanup after show failure also failed.", cleanupError)
            }
            activeSession = null
            analysisUiState = AnalysisUiState.IDLE
            return false
        }
    }

    /** 隐藏（关闭）悬浮窗预览面板 */
    @Synchronized
    fun hide(reason: String = "(none)"): Boolean {
        val session = activeSession
        if (session == null) {
            Log.d(TAG, "[Overlay] hide ignored. reason=$reason, no session.")
            return false
        }

        activeSession = null
        analysisUiState = AnalysisUiState.IDLE

        return try {
            session.manager.removeView(session.rootView)
            OverlayNotificationService.stop(session.rootView.context)
            Log.i(TAG, "[Overlay] window removed. reason=$reason")
            true
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "[Overlay] window was already detached. reason=$reason", error)
            false
        } catch (error: Exception) {
            Log.e(TAG, "[Overlay] unable to remove window. reason=$reason", error)
            false
        }
    }

    // ==================== 自动操作控件绑定 ====================

    /** 绑定自动操作开关和延迟滑块 */
    private fun bindAutoOperationControls(
        appContext: Context,
        view: View,
    ) {
        val autoOpSwitch = view.findViewById<SwitchCompat>(R.id.overlayAutoOpSwitch)
        val autoOpStatus = view.findViewById<TextView>(R.id.overlayAutoOpStatus)
        val autoOpDelaySlider = view.findViewById<SeekBar>(R.id.overlayAutoOpDelaySlider)
        val autoOpDelayValue = view.findViewById<TextView>(R.id.overlayAutoOpDelayValue)

        // 从 SharedPreferences 恢复设置
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoOpEnabled = prefs.getBoolean("auto_op_enabled", false)
        val autoOpDelayProgress = prefs.getInt("auto_op_delay", 10) // 0~50 → 0~500ms

        autoOpSwitch?.isChecked = autoOpEnabled
        autoOpDelaySlider?.progress = autoOpDelayProgress
        updateAutoOpStatus(autoOpStatus, autoOpEnabled, false, appContext)

        // 更新延迟显示文本
        autoOpDelayValue?.text = "${autoOpDelayProgress * 10} ms"

        // 绑定自动操作开关
        autoOpSwitch?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_op_enabled", isChecked).apply()
            hudRenderer?.autoOperationEnabled = isChecked
            updateAutoOpStatus(autoOpStatus, isChecked, false, appContext)
        }

        // 绑定延迟滑块
        autoOpDelaySlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val ms = progress * 10
                autoOpDelayValue?.text = "$ms ms"
                hudRenderer?.autoOperationDelayMs = ms
                prefs.edit().putInt("auto_op_delay", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /** 更新自动操作状态文本 */
    private fun updateAutoOpStatus(
        textView: TextView?,
        enabled: Boolean,
        paused: Boolean,
        ctx: Context,
    ) {
        textView?.text = when {
            !enabled -> ctx.getString(R.string.overlay_auto_op_status_disabled)
            paused -> ctx.getString(R.string.overlay_auto_op_status_paused)
            else -> ctx.getString(R.string.overlay_auto_op_status_enabled)
        }
    }

    // ==================== 原有辅助方法 ====================

    /** 绑定悬浮窗当前可用的透明度调节控件 */
    private fun setupAdjustmentSliders(
        context: Context,
        view: View,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
    }

    /** 将 SharedPreferences 中保存的调节参数应用到悬浮窗 View 上 */
    private fun restoreAdjustments(
        context: Context,
        view: View,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alpha = prefs.getFloat(KEY_ALPHA, 1.0f)
        val radiusDp = prefs.getInt(KEY_RADIUS, 20)

        view.alpha = alpha

        val radiusPx = (radiusDp * context.resources.displayMetrics.density).roundToInt()
        applyOverlayCornerRadius(view, radiusPx)

        val alphaSlider = view.findViewById<SeekBar>(R.id.overlayAlphaSlider)
        val alphaValue = view.findViewById<TextView>(R.id.overlayAlphaValue)
        alphaSlider?.progress = (alpha * 100).toInt()
        alphaValue?.text = "${alphaSlider.progress}%"
    }

    /** dp 转 px */
    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    /** 将悬浮窗背景的圆角半径设置为指定值 */
    private fun applyOverlayCornerRadius(view: View, radiusPx: Int) {
        val drawable = view.background as? GradientDrawable
        if (drawable == null) {
            Log.w(TAG, "[Overlay] root background is not a GradientDrawable; corner radius update skipped.")
            return
        }
        drawable.mutate()
        drawable.cornerRadius = radiusPx.toFloat()
    }
}
