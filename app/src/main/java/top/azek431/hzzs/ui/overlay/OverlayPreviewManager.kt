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
// - 悬浮窗位置、透明度、缩放系数的持久化存储与恢复
//
// 触摸交互逻辑：
// - 拖动：仅在顶部标题栏（overlayDragHandle）区域响应，× 按钮不受拖动影响
// - 缩放：仅在右下角缩放手柄（overlayResizeHandle）区域响应
// - 其他区域不响应拖动或缩放，点击事件交由子控件自行处理
//
// 预留接口：
// - MediaProjection（屏幕采集）
// - 真实帧分析
// - 障碍识别

package top.azek431.hzzs.ui.overlay

import android.content.Context
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
import kotlin.math.roundToInt
import top.azek431.hzzs.R
import top.azek431.hzzs.model.RectF
import top.azek431.hzzs.service.OverlayNotificationService
import top.azek431.hzzs.ui.community.CommunityLinks

object OverlayPreviewManager {

    /** 日志标签 */
    private const val TAG = "HZZS"

    /** SharedPreferences 文件名 */
    private const val PREFS_NAME = "hzzs_overlay_prefs"

    /** 透明度参数键 */
    private const val KEY_ALPHA = "overlay_alpha"

    /** 圆角半径参数键 */
    private const val KEY_RADIUS = "overlay_radius"

    /** 悬浮窗缩放系数 */
    private const val KEY_SCALE_RATIO = "overlay_scale_ratio"

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

    /**
     * 检查当前是否有悬浮窗正在显示。
     *
     * @return true 如果 activeSession 不为 null（即有悬浮窗正在显示）
     */
    @Synchronized
    fun isShowing(): Boolean = activeSession != null

    // ==================== 显示/隐藏 ====================

    /**
     * 创建并显示悬浮窗预览面板。
     *
     * 完整流程：
     * 1. 权限检查：SYSTEM_ALERT_WINDOW 权限是否已授予
     * 2. 去重检查：是否已有活跃会话
     * 3. 创建 WindowManager.LayoutParams（TYPE_APPLICATION_OVERLAY / TYPE_PHONE）
     * 4. inflate 布局文件 view_overlay_preview.xml
     * 5. 绑定所有子控件的点击事件（关闭/开始分析/社区链接/透明度/自动操作）
     * 6. 设置拖动和缩放逻辑
     * 7. 从 SharedPreferences 恢复上次保存的参数
     * 8. 初始化 HUD 渲染器并绑定视图引用
     * 9. 添加到 WindowManager 并启动前台通知服务
     *
     * @param context 上下文（使用 applicationContext 避免内存泄漏）
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
            val dragHandle = view.findViewById<View>(R.id.overlayDragHandle)
                ?: throw IllegalStateException("overlayDragHandle is missing.")
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
            val rootPanel = view.findViewById<View>(R.id.overlayRootPanel)
                ?: throw IllegalStateException("overlayRootPanel is missing.")

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

            // ---- 拖动与缩放逻辑 ----
            /** 基础宽度（px），所有缩放计算以此为基准 */
            val baseWidthPx = dp(appContext, 228)
            /** 缩放系数（相对初始宽度的倍数），用于持久化 */
            var scaleRatio = 1f
            /** 初始宽度（px），用于缩放计算 */
            var initialWidth = 0
            /** 拖动/缩放的起始坐标快照（供 dragHandle 和 resizeHandle 共享） */
            var downRawX = 0f
            var downRawY = 0f
            var downWindowX = 0
            var downWindowY = 0
            /** 是否已确认启动拖动（超过阈值后才为 true） */
            var dragStarted = false
            /** 最小拖动距离（像素），避免手指抖动误触 */
            val MIN_DRAG_DISTANCE_PX = 10

            // 标题栏拖动：仅在 overlayDragHandle 区域响应拖动事件
            dragHandle.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        downWindowX = layoutParams.x
                        downWindowY = layoutParams.y
                        dragStarted = false
                        false // 让标题栏自身 clickable 事件正常响应
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toInt()

                        if (!dragStarted && distance < MIN_DRAG_DISTANCE_PX) {
                            return@setOnTouchListener false
                        }
                        if (!dragStarted) {
                            dragStarted = true
                        }

                        val screenHeight = appContext.resources.displayMetrics.heightPixels
                        val maxY = (screenHeight - view.height).coerceAtLeast(0)
                        layoutParams.x = (downWindowX + dx).coerceIn(0, maxX)
                        layoutParams.y = (downWindowY + dy).coerceIn(0, maxY)

                        try {
                            manager.updateViewLayout(view, layoutParams)
                        } catch (e: IllegalArgumentException) {
                            Log.w(TAG, "[Overlay] detached while dragging.", e)
                        }
                        true
                    }
                    else -> false
                }
            }

            // 缩放手柄缩放：仅在右下角 resizeHandle 区域响应
            resizeHandle?.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        initialWidth = view.measuredWidth.takeIf { it > 0 } ?: baseWidthPx
                        scaleRatio = layoutParams.width.toFloat() / initialWidth
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        val delta = (dx + dy) / 2 // 取 x 和 y 变化的平均值，使缩放更平滑
                        val newWidth = (initialWidth + delta).coerceIn(
                            (initialWidth * 0.5).toInt(),
                            (initialWidth * 2.0).toInt()
                        )
                        layoutParams.width = newWidth
                        scaleRatio = newWidth.toFloat() / initialWidth

                        try {
                            manager.updateViewLayout(view, layoutParams)
                        } catch (e: IllegalArgumentException) {
                            Log.w(TAG, "[Overlay] detached while resizing.", e)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // 保存缩放系数到 SharedPreferences
                        if (scaleRatio != 1.0f) {
                            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            prefs.edit().putFloat(KEY_SCALE_RATIO, scaleRatio).apply()
                        }
                        true
                    }
                    else -> false
                }
            }

            // 关闭按钮
            closeButton.setOnClickListener {
                Log.i(TAG, "[Overlay] close requested.")
                hide("close-button")
            }

            // 触摸监听器绑定到根布局：拦截非拖动/非缩放区域的点击，防止穿透到下层
            rootPanel.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        downWindowX = layoutParams.x
                        downWindowY = layoutParams.y
                        true
                    }
                    else -> false
                }
            }

            // 恢复上次保存的参数
            restoreAdjustments(appContext, view)

            // 初始化 HUD 渲染器（仅驱动模拟帧生成 + C++ 引擎，不再绑定 UI 视图）
            hudRenderer = OverlayHUDRenderer(appContext)

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

    /**
     * 隐藏（关闭）悬浮窗预览面板。
     *
     * 清理流程：
     * 1. 从 WindowManager 中移除 View
     * 2. 停止前台通知服务
     * 3. 清除 activeSession 和分析状态
     *
     * @param reason 调试用原因说明
     * @return true 如果成功移除，false 如果 session 为空或移除失败
     */
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

    // ==================== 原有辅助方法 ====================

    /**
     * 绑定透明度调节滑块到 UI 组件。
     *
     * SeekBar 范围 0~100，映射到 0.0~1.0 的 alpha 值，
     * 实时应用到整个悬浮窗 View 的 alpha 属性上。
     * 用户调整后通过 apply() 写入 SharedPreferences。
     */
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

    /**
     * 将 SharedPreferences 中保存的调节参数应用到悬浮窗 View 上。
     *
     * 恢复内容：
     * 1. 透明度（alpha）— 直接设置 view.alpha
     * 2. 圆角半径（radiusDp）— 转换为 px 后通过 GradientDrawable 设置
     * 3. 透明度滑块的进度值和显示文本
     * 4. 悬浮窗缩放系数（scaleRatio）— 直接设置 view.layoutParams.width
     */
    private fun restoreAdjustments(
        context: Context,
        view: View,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alpha = prefs.getFloat(KEY_ALPHA, 1.0f)
        val radiusDp = prefs.getInt(KEY_RADIUS, 20)
        val savedScale = prefs.getFloat(KEY_SCALE_RATIO, 1.0f)

        view.alpha = alpha

        val radiusPx = (radiusDp * context.resources.displayMetrics.density).roundToInt()
        applyOverlayCornerRadius(view, radiusPx)

        val alphaSlider = view.findViewById<SeekBar>(R.id.overlayAlphaSlider)
        val alphaValue = view.findViewById<TextView>(R.id.overlayAlphaValue)
        alphaSlider?.progress = (alpha * 100).toInt()
        alphaValue?.text = "${alphaSlider.progress}%"

        // 恢复缩放系数
        if (savedScale != 1.0f) {
            val baseWidth = dp(context, 228)
            val scaledWidth = (baseWidth * savedScale).toInt().coerceIn(
                (baseWidth * 0.5).toInt(),
                (baseWidth * 2.0).toInt()
            )
            val lp = view.layoutParams
            lp.width = scaledWidth
            view.layoutParams = lp
        }
    }

    /**
     * dp 转 px 工具方法。
     *
     * @param value dp 值
     * @return 对应的 px 值（四舍五入）
     */
    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    /**
     * 将悬浮窗背景的圆角半径设置为指定值。
     *
     * 通过 GradientDrawable.mutate() + setCornerRadii 实现。
     * 如果背景不是 GradientDrawable（如被其他 drawable 替换），则跳过并记录警告。
     *
     * @param radiusPx 圆角半径（像素）
     */
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
