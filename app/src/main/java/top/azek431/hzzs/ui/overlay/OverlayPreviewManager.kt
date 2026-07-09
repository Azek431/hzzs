// 火崽崽助手（HZZS）悬浮窗管理器。
//
// 单例对象，负责管理系统级悬浮窗（WindowManager Overlay）的完整生命周期：
// 创建、显示、拖动、关闭、参数持久化和恢复。
//
// 当前负责的功能：
// - 显示、拖动、关闭悬浮窗预览面板
// - 循环执行 / 单次执行 按钮驱动 HUD 模拟帧循环
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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import top.azek431.hzzs.NativeAnalysisBridge
import top.azek431.hzzs.R
import top.azek431.hzzs.model.RectF
import top.azek431.hzzs.service.OverlayNotificationService
import top.azek431.hzzs.ui.community.CommunityLinks
import top.azek431.hzzs.ui.overlay.VisionDebugOverlayView

/**
 * 悬浮窗管理器单例。
 *
 * 这是悬浮窗系统的入口点，负责：
 * 1. 权限检查
 * 2. 去重检查（防止重复创建）
 * 3. 创建 WindowManager.LayoutParams（委托给 OverlayWindowController）
 * 4. inflate 布局文件
 * 5. 查找必需子控件
 * 6. 绑定开始/停止分析按钮
 * 7. 绑定社区链接
 * 8. 初始化拖动/缩放/设置控制器
 * 9. 初始化 HUD 渲染器
 * 10. 添加到 WindowManager 并启动前台通知服务
 * 11. 隐藏/关闭悬浮窗
 *
 * 设计原因：
 * - 保持单例模式，确保全局只有一个悬浮窗实例
 * - 所有子控件绑定逻辑委托给独立的 Controller 类
 * - show/hide 方法使用 @Synchronized 确保线程安全
 */
object OverlayPreviewManager {

    /** 日志标签 */
    private const val TAG = "HZZS"

    // ==================== HUD 渲染器 ====================

    /**
     * 悬浮窗根 View（overlayRootPanel）。
     *
     * 用于初始化拖动控制器时记录起始位置。
     * 在 rootPanel 的 onTouchListener 中，ACTION_DOWN 时调用
     * dragController.recordStartPosition() 保存当前 layoutParams.x/y。
     */
    private var rootView: View? = null

    /** HUD 渲染器实例，负责模拟帧生成和 C++ 引擎驱动 */
    private var hudRenderer: OverlayHUDRenderer? = null

    /** 视觉调试叠加层，用于可视化识别框和扫描线 */
    private var visionDebugOverlay: VisionDebugOverlayView? = null

    // ==================== 悬浮窗会话 ====================

    /** 当前活跃的悬浮窗会话 */
    private data class OverlaySession(
        val rootView: View,
        val manager: WindowManager,
    )

    /**
     * 当前活跃的悬浮窗会话。
     *
     * 包含悬浮窗根 View 和 WindowManager 实例。
     * show() 成功后创建，hide() 时清除。
     * 为 null 表示当前没有悬浮窗显示。
     */
    private var activeSession: OverlaySession? = null

    // ==================== 数据分析状态 ====================

    /**
     * 分析执行状态枚举。
     *
     * CYCLE_RUNNING — 循环执行中（持续生成模拟帧）
     * SINGLE_PENDING  — 单次执行待触发（按钮刚按下，等待 Handler 回调）
     * IDLE            — 空闲
     */
    private enum class AnalysisUiState {
        CYCLE_RUNNING,
        SINGLE_PENDING,
        IDLE,
    }

    @Volatile
    private var analysisUiState = AnalysisUiState.IDLE

    /** 单次执行的 Handler，用于 400ms 后自动清空状态 */
    private val singleHandler = Handler(Looper.getMainLooper())

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
     * 3. 创建 WindowManager.LayoutParams（委托给 OverlayWindowController）
     * 4. inflate 布局文件 view_overlay_preview.xml
     * 5. 绑定所有子控件的点击事件（关闭/开始分析/社区链接）
     * 6. 初始化拖动/缩放/设置控制器
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
            val statusDot = view.findViewById<View>(R.id.overlayStatusDot)
                ?: throw IllegalStateException("overlayStatusDot is missing.")
            val btnCycle = view.findViewById<TextView>(R.id.overlayBtnCycle)
                ?: throw IllegalStateException("overlayBtnCycle is missing.")
            val btnSingle = view.findViewById<TextView>(R.id.overlayBtnSingle)
                ?: throw IllegalStateException("overlayBtnSingle is missing.")
            val communityQq = view.findViewById<View>(R.id.overlayCommunityQq)
                ?: throw IllegalStateException("overlayCommunityQq is missing.")
            val communityTelegram = view.findViewById<View>(R.id.overlayCommunityTelegram)
                ?: throw IllegalStateException("overlayCommunityTelegram is missing.")
            val resizeHandle = view.findViewById<View>(R.id.overlayResizeHandle)
            val rootPanel = view.findViewById<View>(R.id.overlayRootPanel)
                ?: throw IllegalStateException("overlayRootPanel is missing.")

            // 创建窗口参数控制器
            val windowController = OverlayWindowController(appContext)
            val layoutParams = windowController.createLayoutParams()

            // 绑定循环执行 / 单次执行按钮
            // 辅助函数：更新状态指示器外观
            fun updateStatusUI(isRunning: Boolean) {
                if (isRunning) {
                    statusText.setText(R.string.overlay_analysis_running)
                    statusText.setTextColor(view.context.getColor(android.R.color.holo_blue_light))
                    statusDot.setBackgroundColor(view.context.getColor(android.R.color.holo_blue_light))
                    btnCycle.setText(R.string.overlay_btn_cycle_stop)
                    btnCycle.setBackgroundResource(R.drawable.bg_overlay_btn_single)
                    btnSingle.isEnabled = false
                    btnSingle.alpha = 0.4f
                } else {
                    statusText.setText(R.string.overlay_preview_status)
                    statusText.setTextColor(view.context.getColor(android.R.color.darker_gray))
                    statusDot.setBackgroundColor(view.context.getColor(android.R.color.holo_blue_dark))
                    btnCycle.setText(R.string.overlay_btn_cycle_start)
                    btnCycle.setBackgroundResource(R.drawable.bg_overlay_btn_cycle)
                    btnSingle.isEnabled = true
                    btnSingle.alpha = 1f
                }
            }

            // 循环执行按钮：点击切换 循环执行 <-> 停止运行
            btnCycle.setOnClickListener {
                when (analysisUiState) {
                    AnalysisUiState.IDLE -> {
                        analysisUiState = AnalysisUiState.CYCLE_RUNNING
                        updateStatusUI(true)
                        Log.i(TAG, "[Analysis] cycle execution started.")
                        Toast.makeText(appContext, R.string.overlay_analysis_started, Toast.LENGTH_SHORT).show()
                        hudRenderer?.start()
                    }
                    AnalysisUiState.CYCLE_RUNNING -> {
                        analysisUiState = AnalysisUiState.IDLE
                        updateStatusUI(false)
                        Log.i(TAG, "[Analysis] cycle execution stopped.")
                        Toast.makeText(appContext, R.string.overlay_analysis_stopped, Toast.LENGTH_SHORT).show()
                        hudRenderer?.stop()
                        NativeAnalysisBridge.resetEngine()
                        visionDebugOverlay?.stopAnimation()
                    }
                    AnalysisUiState.SINGLE_PENDING -> {
                        // 用户在单次执行等待中点击循环按钮，取消单次并启动循环
                        singleHandler.removeCallbacksAndMessages(null)
                        analysisUiState = AnalysisUiState.CYCLE_RUNNING
                        updateStatusUI(true)
                        Log.i(TAG, "[Analysis] switched from single to cycle execution.")
                        hudRenderer?.start()
                    }
                }
            }

            // 单次执行按钮：点击后执行一次分析，400ms 后自动恢复空闲状态
            btnSingle.setOnClickListener {
                // 如果循环执行正在运行，先停止循环
                if (analysisUiState == AnalysisUiState.CYCLE_RUNNING) {
                    singleHandler.removeCallbacksAndMessages(null)
                    analysisUiState = AnalysisUiState.IDLE
                    hudRenderer?.stop()
                    visionDebugOverlay?.stopAnimation()
                    updateStatusUI(false)
                }

                // 防止重复点击
                if (analysisUiState == AnalysisUiState.SINGLE_PENDING) return@setOnClickListener

                analysisUiState = AnalysisUiState.SINGLE_PENDING
                updateStatusUI(true)
                Log.i(TAG, "[Analysis] single execution triggered.")
                Toast.makeText(appContext, R.string.overlay_single_started, Toast.LENGTH_SHORT).show()

                // 执行单次分析
                hudRenderer?.startSingle()

                // 400ms 后自动恢复空闲状态
                singleHandler.postDelayed({
                    if (analysisUiState == AnalysisUiState.SINGLE_PENDING) {
                        analysisUiState = AnalysisUiState.IDLE
                        updateStatusUI(false)
                        Log.i(TAG, "[Analysis] single execution auto-cleared after 400ms.")
                    }
                }, 400)
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

            // 初始化拖动控制器：处理 overlayDragHandle 的拖动事件
            // 拖动起始位置由 rootPanel 的 onTouchListener 在 ACTION_DOWN 时记录
            val dragController = OverlayDragController(dragHandle, object : OnDragUpdateListener {
                override fun onDragUpdated(x: Int, y: Int) {
                    // 限制 X 坐标不超出屏幕右边界
                    layoutParams.x = x.coerceIn(0, windowController.calculateMaxX(layoutParams.width))
                    // 限制 Y 坐标不超出屏幕下边界
                    layoutParams.y = y.coerceIn(0, windowController.calculateMaxY(view.height))
                    try {
                        manager.updateViewLayout(view, layoutParams)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "[Overlay] detached while dragging.", e)
                    }
                }
            })
            dragController.attach()

            // 初始化缩放控制器：处理 overlayResizeHandle 的缩放事件
            val resizeListener = object : OnResizeUpdateListener {
                override fun onResized(newWidth: Int, scaleRatio: Float) {
                    layoutParams.width = newWidth
                    try {
                        manager.updateViewLayout(view, layoutParams)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "[Overlay] detached while resizing.", e)
                    }
                }
            }
            val resizeController = OverlayResizeController(resizeHandle, appContext, resizeListener)
            resizeController.attach()

            // 初始化设置绑定器：绑定透明度滑块和自动操作控件
            val settingsBinder = OverlaySettingsBinder(appContext, view)
            settingsBinder.bind()
            settingsBinder.restoreAll(baseWidth = windowController.baseWidthPx())

            // 关闭按钮：点击后隐藏悬浮窗
            closeButton.setOnClickListener {
                Log.i(TAG, "[Overlay] close requested.")
                hide("close-button")
            }

            // 触摸监听器绑定到根布局：
            // ACTION_DOWN 时记录拖动起始位置，防止拖动时位置跳变
            // 其他事件不处理，让子控件自行响应
            rootPanel.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragController.recordStartPosition(layoutParams.x, layoutParams.y)
                        true
                    }
                    else -> false
                }
            }

            // 初始化 HUD 渲染器（仅驱动模拟帧生成 + C++ 引擎，不再绑定 UI 视图）
            hudRenderer = OverlayHUDRenderer(appContext)

            // 查找并绑定视觉调试叠加层
            visionDebugOverlay = view.findViewById(R.id.visionDebugOverlay)
            visionDebugOverlay?.startAnimation()

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
            singleHandler.removeCallbacksAndMessages(null)
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
        singleHandler.removeCallbacksAndMessages(null)
        visionDebugOverlay?.stopAnimation()

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
}
