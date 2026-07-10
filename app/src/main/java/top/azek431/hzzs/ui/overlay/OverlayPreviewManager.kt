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
// - 悬浮窗位置、透明度的持久化存储与恢复（缩放不持久化，每次打开恢复默认）
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
//
// 设计原因：
// - show() 方法中所有子职责已委托给独立 Controller 类
// - OverlayViewFinder — 视图查找
// - OverlayButtonBinder — 按钮绑定和状态管理
// - OverlayDragController — 拖动逻辑
// - OverlayResizeController — 缩放逻辑
// - OverlaySettingsBinder — 设置绑定
// - OverlayHUDRenderer — HUD 渲染
// - 本类只负责 show/hide/isShowing 和会话生命周期

package top.azek431.hzzs.ui.overlay

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import top.azek431.hzzs.core.NativeAnalysisBridge
import top.azek431.hzzs.R
import top.azek431.hzzs.service.OverlayNotificationService
import top.azek431.hzzs.ui.community.CommunityLinks

/**
 * 悬浮窗管理器单例。
 *
 * 这是悬浮窗系统的入口点，负责：
 * 1. 权限检查
 * 2. 去重检查（防止重复创建）
 * 3. 创建 WindowManager.LayoutParams（委托给 OverlayWindowController）
 * 4. inflate 布局文件
 * 5. 查找必需子控件（委托给 OverlayViewFinder）
 * 6. 绑定开始/停止分析按钮（委托给 OverlayButtonBinder）
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

    /** HUD 渲染器实例，负责模拟帧生成和 C++ 引擎驱动 */
    private var hudRenderer: OverlayHUDRenderer? = null

    /** 屏幕调试叠加视图，全屏显示分析结果绘制 */
    private var screenOverlay: ScreenOverlayView? = null

    /** 屏幕叠加视图的 WindowManager 参数 */
    private var screenOverlayParams: WindowManager.LayoutParams? = null

    /** 屏幕叠加视图的 WindowManager 实例（用于 hide 时移除） */
    private var screenOverlayManager: WindowManager? = null

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
    @Suppress("DEPRECATION")
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
     * 5. 查找必需子控件（委托给 OverlayViewFinder）
     * 6. 绑定按钮（委托给 OverlayButtonBinder）
     * 7. 绑定社区链接
     * 8. 初始化拖动/缩放/设置控制器
     * 9. 初始化 HUD 渲染器
     * 10. 添加到 WindowManager 并启动前台通知服务
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

            // 查找所有必需子控件（委托给 OverlayViewFinder）
            val finder = OverlayViewFinder(appContext, view)

            // 创建窗口参数控制器
            val windowController = OverlayWindowController(appContext)
            val layoutParams = windowController.createLayoutParams()

            // 绑定按钮（循环执行/单次执行）（委托给 OverlayButtonBinder）
            val buttonBinder = OverlayButtonBinder(
                finder.statusText, finder.statusDot, finder.btnCycle, finder.btnSingle, appContext
            )
            buttonBinder.bind(
                startCycle = {
                    analysisUiState = AnalysisUiState.CYCLE_RUNNING
                    hudRenderer?.start()
                },
                stopCycle = {
                    analysisUiState = AnalysisUiState.IDLE
                    hudRenderer?.stop()
                    NativeAnalysisBridge.resetEngine()
                },
                reset = {
                    singleHandler.removeCallbacksAndMessages(null)
                    analysisUiState = AnalysisUiState.IDLE
                },
                startSingle = {
                    hudRenderer?.startSingle()
                },
            )

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

            // 绑定配置入口 → 打开 VisionSettingsActivity
            finder.settingsEntry.setOnClickListener {
                val intent = android.content.Intent(appContext, top.azek431.hzzs.ui.settings.VisionSettingsActivity::class.java)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            }

            // 初始化拖动控制器
            val dragController = OverlayDragController(finder.dragHandle, object : OnDragUpdateListener {
                override fun onDragUpdated(x: Int, y: Int) {
                    layoutParams.x = x.coerceIn(0, windowController.calculateMaxX(layoutParams.width))
                    layoutParams.y = y.coerceIn(0, windowController.calculateMaxY(view.height))
                    try {
                        manager.updateViewLayout(view, layoutParams)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "[Overlay] detached while dragging.", e)
                    }
                }
            })
            dragController.attach(layoutParams.x, layoutParams.y)

            // 初始化缩放控制器
            val resizeController = OverlayResizeController(finder.resizeHandle, appContext, object : OnResizeUpdateListener {
                override fun onResized(newWidth: Int, newHeight: Int) {
                    layoutParams.width = newWidth
                    layoutParams.height = newHeight
                    try {
                        manager.updateViewLayout(view, layoutParams)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "[Overlay] detached while resizing.", e)
                    }
                }
            })
            resizeController.initializeDimensions(view)
            resizeController.attach()

            // 初始化设置绑定器
            val settingsBinder = OverlaySettingsBinder(appContext, view)
            settingsBinder.bind()
            settingsBinder.restoreAll(baseWidth = windowController.baseWidthPx())

            // 关闭按钮
            finder.closeButton.setOnClickListener {
                Log.i(TAG, "[Overlay] close requested.")
                hide("close-button")
            }

            // 初始化 HUD 渲染器
            hudRenderer = OverlayHUDRenderer(appContext)

            // 创建屏幕调试叠加视图（全屏绘制分析结果）
            screenOverlay = ScreenOverlayView(appContext).apply {
                isClickable = false
                isFocusable = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            // 设置屏幕叠加视图的窗口参数
            screenOverlayParams = WindowManager.LayoutParams().apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                // 悬浮窗类型：在所有窗口之上
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                flags = (WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                format = android.graphics.PixelFormat.TRANSLUCENT
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }

            // 连接渲染器 → 叠加视图：每次分析结果出来后自动绘制到屏幕上
            val overlayView = screenOverlay!!
            hudRenderer?.setOnFrameResultListener { result ->
                overlayView.updateResult(result)
            }

            // 连接视觉识别结果 → 叠加视图
            hudRenderer?.setOnVisualRecognitionListener {
                bottleFound, bottleLeft, bottleRight, bottleCenterX, bottleScanY, bottleWidth, bottleConfidence, bottleCostMs,
                pitFound, pitLeft, pitRight, pitCenterX, pitScanY, pitWidth, pitEdgeGap, pitConfidence ->

                overlayView.bottleFound = bottleFound
                overlayView.bottleLeft = bottleLeft
                overlayView.bottleRight = bottleRight
                overlayView.bottleCenterX = bottleCenterX
                overlayView.bottleCenterY = bottleScanY
                overlayView.bottleWidth = bottleWidth
                overlayView.bottleScanY = bottleScanY
                overlayView.bottleConfidence = bottleConfidence
                overlayView.bottleCostMs = bottleCostMs

                overlayView.pitFound = pitFound
                overlayView.pitLeft = pitLeft
                overlayView.pitRight = pitRight
                overlayView.pitCenterX = pitCenterX
                overlayView.pitScanY = pitScanY
                overlayView.pitWidth = pitWidth
                overlayView.pitEdgeGap = pitEdgeGap
                overlayView.pitConfidence = pitConfidence
            }

            // 添加到 WindowManager（在悬浮窗之后）
            try {
                manager.addView(screenOverlay, screenOverlayParams)
                screenOverlayManager = manager
            } catch (e: Exception) {
                Log.w(TAG, "[Overlay] failed to add screen overlay: ${e.message}")
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
            singleHandler.removeCallbacksAndMessages(null)
            screenOverlay?.let {
                try { screenOverlayManager?.removeView(it) } catch (e: Exception) { /* ignore */ }
            }
            screenOverlay = null
            screenOverlayParams = null
            hudRenderer = null
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

        // 清理屏幕叠加视图
        screenOverlay?.let { ov ->
            screenOverlayManager?.removeView(ov)
            screenOverlayManager = null
            screenOverlay = null
            screenOverlayParams = null
        }

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
