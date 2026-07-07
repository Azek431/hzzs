package top.azek431.hzzs

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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * 火崽崽助手（HZZS）第一阶段悬浮窗管理器。
 *
 * 这是一个单例对象（object），负责管理系统级悬浮窗（WindowManager Overlay）的完整生命周期：
 * 创建、显示、拖动、关闭、参数持久化和恢复。
 *
 * 当前负责的功能：
 * - 显示、拖动、关闭悬浮窗预览面板
 * - 数据分析准备入口（开始/停止按钮，当前仅占位）
 * - QQ 群与 Telegram 主频道跳转（嵌入悬浮窗内）
 * - 悬浮窗透明度调节滑块
 * - 悬浮窗位置、透明度参数的持久化存储与恢复
 *
 * 当前不接入的功能（预留接口）：
 * - MediaProjection（屏幕采集）
 * - 真实帧分析
 * - 障碍识别
 * - 自动操作
 *
 * 架构说明：
 * - 使用 WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 创建系统级悬浮窗
 * - 悬浮窗布局来自 view_overlay_preview.xml
 * - 拖动通过 OnTouchListener + MotionEvent 实现
 * - 参数通过 SharedPreferences 持久化
 * - 使用 @Synchronized 保证多线程安全
 *
 * 权限要求：
 * - AndroidManifest.xml 中声明 SYSTEM_ALERT_WINDOW 权限
 * - 运行时需用户手动在系统设置中授权
 * - Build.VERSION_CODES.M (Android 6.0) 以上需要检查 Settings.canDrawOverlays()
 */
object OverlayPreviewManager {

    /** 日志标签，用于区分 HZZS 各模块的日志输出 */
    private const val TAG = "HZZS"

    /** SharedPreferences 文件名，用于存储悬浮窗相关参数 */
    private const val PREFS_NAME = "hzzs_overlay_prefs"

    /** 透明度参数键（范围 0.0 ~ 1.0，存储为 Float） */
    private const val KEY_ALPHA = "overlay_alpha"

    /** 圆角半径参数键（单位：dp，存储为 Int） */
    private const val KEY_RADIUS = "overlay_radius"

    /**
     * 悬浮窗内数据分析 UI 的状态枚举。
     *
     * 当前仅有两个状态，用于控制"开始执行/点击结束执行"按钮的文本和语义：
     * - IDLE：空闲状态，按钮显示"开始执行"，点击后进入 EXECUTING
     * - EXECUTING：执行中状态，按钮显示"点击结束执行"，点击后回到 IDLE
     *
     * 注意：当前"执行"仅为 UI 占位，尚未接入真实的屏幕采集和分析引擎。
     */
    private enum class AnalysisUiState {
        /** 空闲：未在执行任何分析任务 */
        IDLE,

        /** 执行中：已启动分析（当前仅改变 UI 状态） */
        EXECUTING,
    }

    /**
     * 悬浮窗会话的数据类。
     *
     * 封装了一个活跃悬浮窗所需的全部引用：
     * - rootView：悬浮窗的根 View，用于移除和更新布局
     * - manager：WindowManager 实例，用于 addView/removeView/updateViewLayout
     *
     * 使用 data class 而非普通 class，以便在调试时能自动打印字段值。
     */
    private data class OverlaySession(
        val rootView: View,
        val manager: WindowManager,
    )

    /**
     * 当前活跃的悬浮窗会话。
     *
     * 如果为 null，表示当前没有悬浮窗在显示中。
     * 如果非 null，表示悬浮窗已创建且正在系统中可见。
     *
     * 设计决策：使用此内部引用而非 View.isAttachedToWindow 来判断状态，
     * 因为某些设备在 addView() 后短时间内仍可能返回 false，
     * 但系统 Overlay 实际已经显示。若此时清空引用，将无法关闭窗口。
     */
    private var activeSession: OverlaySession? = null

    /**
     * 当前数据分析 UI 的状态（@Volatile 保证多线程可见性）。
     *
     * 使用 @Volatile 而非 @Synchronized 修饰变量，因为：
     * - analysisUiState 只在 show() 和 hide() 中被读写
     * - show() 和 hide() 本身已由 @Synchronized 保护
     * - @Volatile 确保当一个线程修改状态后，其他线程立即可见
     * - 比 synchronized 块开销更小
     *
     * 注意：analysisUiState 与 activeSession 是两个独立的状态：
     * - activeSession：控制悬浮窗是否可见（物理层面）
     * - analysisUiState：控制"开始执行/结束执行"按钮的文本（逻辑层面）
     * 关闭悬浮窗时两者都重置，但它们是正交的概念。
     */
    @Volatile
    private var analysisUiState = AnalysisUiState.IDLE

    /**
     * 检查当前是否有悬浮窗正在显示。
     *
     * 通过判断 activeSession 是否为 null 来确定状态。
     * 此方法是线程安全的（@Synchronized），可在任意线程调用。
     *
     * @return true 如果悬浮窗正在显示，false 否则
     */
    @Synchronized
    fun isShowing(): Boolean {
        return activeSession != null
    }

    /**
     * 创建并显示悬浮窗预览面板。
     *
     * 这是整个悬浮窗系统的核心方法，执行以下完整流程：
     * 1. 权限检查：验证 SYSTEM_ALERT_WINDOW 权限
     * 2. 去重检查：如果悬浮窗已在显示中，直接返回 true
     * 3. 状态重置：将数据分析状态设为 IDLE
     * 4. 获取 WindowManager 服务
     * 5.  inflate 悬浮窗布局（view_overlay_preview.xml）
     * 6. 查找并绑定所有子控件的点击事件：
     *    - 关闭按钮 → 调用 hide()
     *    - 开始分析按钮 → 切换 IDLE/EXECUTING 状态
     *    - QQ 群链接 → 打开 CommunityLinks.HZZS_QQ_GROUP_URL
     *    - Telegram 链接 → 打开 CommunityLinks.AZEK_MAIN_TELEGRAM_URL
     *    - 拖动手柄 → 处理 MotionEvent 更新 WindowManager.LayoutParams
     *    - 透明度滑块 → 实时更新 view.alpha 并持久化
     * 7. 恢复上次保存的参数（透明度、圆角）
     * 8. 将 View 添加到 WindowManager
     * 9. 保存会话引用到 activeSession
     *
     * @param context 上下文（通常是 Activity 或 Application）
     * @return true 如果悬浮窗成功显示，false 如果权限不足或发生异常
     *
     * 异常处理：
     * - 权限不足：返回 false，不弹出提示（由调用方 MainActivity 统一处理）
     * - 布局文件缺少必需控件：抛出 IllegalStateException
     * - 其他异常：记录 Error 日志，尝试清理已添加的 View，返回 false
     */
    @Synchronized
    fun show(context: Context): Boolean {
        // 使用 ApplicationContext，避免 Activity 被意外持有导致内存泄漏
        val appContext = context.applicationContext

        // 权限检查：Android 6.0+ 需要 SYSTEM_ALERT_WINDOW 权限
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(appContext)
        ) {
            Log.w(TAG, "[Overlay] permission is not granted.")
            return false
        }

        // 去重检查：如果悬浮窗已经在显示中，忽略此次请求
        if (activeSession != null) {
            Log.d(TAG, "[Overlay] show ignored because a session is already active.")
            return true
        }

        // 使用局部变量暂存，以便在异常时进行清理
        var candidateView: View? = null
        var candidateManager: WindowManager? = null

        try {
            // 重置数据分析状态为空闲
            analysisUiState = AnalysisUiState.IDLE

            // 获取 WindowManager 系统服务，用于管理悬浮窗
            val manager = appContext.getSystemService(
                Context.WINDOW_SERVICE,
            ) as WindowManager

            candidateManager = manager

            // 从布局文件 inflate 悬浮窗面板
            val view = LayoutInflater.from(appContext)
                .inflate(R.layout.view_overlay_preview, null, false)

            candidateView = view

            // 查找并验证必需的子控件，任一缺失都会抛出异常
            val closeButton = view.findViewById<View>(R.id.overlayCloseButton)
                ?: throw IllegalStateException("overlayCloseButton is missing.")

            val contentPanel = view.findViewById<View>(R.id.overlayContentPanel)
                ?: throw IllegalStateException("overlayContentPanel is missing.")

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

            val resizeHandle = view.findViewById<View>(R.id.overlayResizeHandle)

            // 计算悬浮窗宽度（228dp → px）和在屏幕上的最大 X 坐标
            val overlayWidth = dp(appContext, 228)
            val screenWidth = appContext.resources.displayMetrics.widthPixels
            val maxX = (screenWidth - overlayWidth).coerceAtLeast(0)

            // 配置悬浮窗的 LayoutParams：
            // - 宽度固定 228dp，高度自适应内容
            // - 使用 TYPE_APPLICATION_OVERLAY（Android 8.0+）或 TYPE_PHONE（低版本兼容）
            // - FLAG_NOT_FOCUSABLE：不拦截触摸事件，允许用户操作底层应用
            // - FLAG_LAYOUT_IN_SCREEN：允许悬浮窗延伸到屏幕边缘
            // - 默认位置：右侧距屏幕边缘 244dp，垂直方向距顶部 108dp
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

            // 绑定开始/停止分析按钮：切换数据分析状态
            startAnalysisButton.setOnClickListener {
                when (analysisUiState) {
                    // 从空闲 → 执行中：切换按钮文本和状态
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

                    // 从执行中 → 空闲：恢复按钮文本和状态
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

            // 绑定社区链接：消费 CommunityLinks.entries 统一配置
            // 通过 labelRes 反查 View（映射表以 R.id 为 key）
            // 扩展方式：新增链接时只需在 CommunityLinks.entries 中添加一项，
            // 并在 overlayLinkViews 映射表中补充 viewId → labelRes 的对应关系
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
                        CommunityLinks.openLink(
                            context = appContext,
                            label = appContext.getString(entry.labelRes),
                            url = entry.url,
                            fallbackMessage = fallbackMsg,
                        )
                    }
                }
            }

            // 绑定调节控件（透明度滑块等）
            setupAdjustmentSliders(appContext, view)

            // ---- 拖动与缩放逻辑 ----
            // 记录手指按下时的原始坐标和窗口位置，用于计算拖动偏移量
            var downRawX = 0f
            var downRawY = 0f
            var downWindowX = 0
            var downWindowY = 0
            var initialWidth = overlayWidth
            var isScaling = false

            // 关闭按钮：点击后隐藏悬浮窗（不触发拖动）
            closeButton.setOnClickListener {
                Log.i(TAG, "[Overlay] close requested.")
                hide("close-button")
            }

            // 为内容面板设置触摸监听器——整个面板都可以拖动。
            //
            // 触摸分发优先级（从快到慢）：
            // 1. ACTION_DOWN：
            //    a) 如果触摸的是可点击子控件（关闭按钮、分析按钮、社区链接）→ 返回 false，
            //       让 onClick 正常触发
            //    b) 否则 → 记录初始位置，返回 true 开始拖动
            // 2. ACTION_MOVE：拖动/缩放
            // 3. ACTION_UP / ACTION_CANCEL：释放 isScaling
            //
            // 缩放手柄区域：右下角 48dp × 48dp 范围内
            // 拖动阈值：5dp（避免轻微抖动被误判为拖动）
            // 宽度限制：0.5x ~ 1.5x 原始宽度
            contentPanel.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    // 手指按下：检查是否点击了可交互子控件
                    MotionEvent.ACTION_DOWN -> {
                        // 识别可点击的子控件：关闭按钮、分析按钮、社区链接、滑块
                        val clickableIds = setOf(
                            R.id.overlayCloseButton,
                            R.id.overlayStartAnalysis,
                            R.id.overlayCommunityQq,
                            R.id.overlayCommunityTelegram,
                            R.id.overlayAlphaSlider,
                        )

                        val touchedView = event.targetView
                        val isClickableChild = touchedView != null &&
                            clickableIds.contains(touchedView.id)

                        if (isClickableChild) {
                            // 触摸的是可点击控件，不拦截，让 onClick 正常触发
                            false
                        } else {
                            // 普通区域：记录初始位置，开始拖动
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

                        // 如果位移超过 5dp 阈值，视为拖动而非点击
                        val dragThresholdPx = dp(appContext, 5)
                        if (!isScaling && (Math.abs(dx) > dragThresholdPx || Math.abs(dy) > dragThresholdPx)) {
                            // 检查是否在缩放手柄区域（右下角 48dp 范围内）
                            // 注意：event.rawX/Y 是相对于 contentPanel 的坐标
                            val panelRight = v.width
                            val panelBottom = v.height
                            val isInResizeCorner = (event.rawX > panelRight - dp(appContext, 48)) &&
                                (event.rawY > panelBottom - dp(appContext, 48))

                            if (isInResizeCorner && resizeHandle != null) {
                                // 在缩放手柄区域：进入缩放模式
                                isScaling = true
                            }
                        }

                        if (isScaling && resizeHandle != null) {
                            // 缩放模式：调整宽度
                            // 限制范围：最小 50% 原始宽度，最大 150% 原始宽度
                            val newWidth = (initialWidth + dx).coerceIn(
                                (initialWidth * 0.5).toInt(),
                                (initialWidth * 1.5).toInt()
                            )
                            layoutParams.width = newWidth
                        } else {
                            // 拖动模式：更新位置
                            layoutParams.x = (downWindowX + dx).coerceIn(0, maxX)
                            layoutParams.y = (downWindowY + dy).coerceAtLeast(0)
                        }

                        // 注意：updateViewLayout 必须用根 View（view），不能用子 View（v）
                        // 因为 layoutParams 是根 View 的 LayoutParams，不是子 View 的
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

                    // 手指抬起或手势取消：释放状态
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        isScaling = false
                        true
                    }

                    else -> false
                }
            }

            // 恢复上次保存的调节参数（透明度、圆角等）
            restoreAdjustments(appContext, view)

            // 将悬浮窗添加到 WindowManager，此时窗口在屏幕上可见
            try {
                manager.addView(view, layoutParams)
            } catch (iae: IllegalStateException) {
                // 视图已附加到 WindowManager（可能因进程重启后残留）。
                // 这种情况通常发生在：用户打开悬浮窗 → 杀进程重启 → 悬浮窗仍存在。
                // 此时 activeSession 为 null（单例已重建），但系统窗口还在。
                // 直接将此 view 注册为 activeSession，避免重复 addView 导致崩溃。
                Log.w(TAG, "[Overlay] view already attached, reusing session.", iae)
                activeSession = OverlaySession(
                    rootView = view,
                    manager = manager,
                )
                return true
            } catch (e: IllegalArgumentException) {
                // WindowManager 拒绝此 view（如权限被撤销）
                throw e
            }

            // 保存会话引用，标记悬浮窗已激活
            activeSession = OverlaySession(
                rootView = view,
                manager = manager,
            )

            Log.i(TAG, "[Overlay] session created and window is visible.")

            return true
        } catch (error: Exception) {
            // 悬浮窗创建失败时的错误处理
            Log.e(TAG, "[Overlay] unable to show preview overlay.", error)

            // 尝试清理部分创建的 View，防止残留
            try {
                if (candidateView != null && candidateManager != null) {
                    // 使用 removeView 而非 removeViewImmediate，因为此时 View 可能
                    // 尚未被 addView（异常发生在 addView 之前），removeViewImmediate
                    // 会抛出 IllegalStateException。removeView 更安全：如果 View
                    // 已附加则移除，如果未附加则无操作。
                    candidateManager.removeView(candidateView)
                }
            } catch (cleanupError: Exception) {
                Log.w(
                    TAG,
                    "[Overlay] cleanup after show failure also failed.",
                    cleanupError,
                )
            }

            // 清理会话引用和状态
            activeSession = null
            analysisUiState = AnalysisUiState.IDLE

            return false
        }
    }

    /**
     * 将 SharedPreferences 中保存的调节参数应用到悬浮窗 View 上。
     *
     * 在悬浮窗创建时调用，恢复用户上次的设置：
     * - 透明度（Float，0.0 ~ 1.0）
     * - 圆角半径（Int，单位 dp）
     * - SeekBar 进度回写（UI 滑块显示上次保存的值）
     *
     * @param context 上下文
     * @param view 悬浮窗根 View
     */
    private fun restoreAdjustments(
        context: Context,
        view: View,
    ) {
        // 读取持久化的参数
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alpha = prefs.getFloat(KEY_ALPHA, 1.0f) // 默认完全不透明

        val radiusDp = prefs.getInt(KEY_RADIUS, 20) // 默认 20dp 圆角

        // 应用透明度到整个悬浮窗
        view.alpha = alpha

        // 应用圆角：dp → px 转换后设置到背景 Drawable 上
        val radiusPx = (radiusDp * context.resources.displayMetrics.density).roundToInt()
        applyOverlayCornerRadius(view, radiusPx)

        // SeekBar 进度回写：更新滑块位置和数值显示
        val alphaSlider = view.findViewById<SeekBar>(R.id.overlayAlphaSlider)
        val alphaValue = view.findViewById<TextView>(R.id.overlayAlphaValue)
        alphaSlider?.progress = (alpha * 100).toInt()
        alphaValue?.text = "${alphaSlider.progress}%"
    }

    /**
     * 隐藏（关闭）悬浮窗预览面板。
     *
     * @param reason 关闭原因，用于日志记录（如 "close-button"、"manual" 等）
     * @return true 如果悬浮窗成功移除，false 如果当前没有悬浮窗或移除失败
     *
     * 线程安全：使用 @Synchronized 保证多线程调用安全。
     *
     * 执行流程：
     * 1. 获取当前活跃会话
     * 2. 如果为空，返回 false（无需重复关闭）
     * 3. 立即清空会话引用和数据分析状态（防止快速重复点击导致重复 removeView）
     * 4. 调用 WindowManager.removeViewImmediate() 移除窗口
     * 5. 捕获可能的异常并记录日志
     */
    @Synchronized
    fun hide(reason: String = "(none)"): Boolean {
        val session = activeSession

        // 如果没有活跃会话，说明悬浮窗已经关闭，直接返回
        if (session == null) {
            Log.d(TAG, "[Overlay] hide ignored. reason=$reason, no session.")
            return false
        }

        /*
         * 先清空会话，阻止多次快速点击导致重复 removeView。
         * removeView 即使窗口被系统提前移除，也只会抛异常并被捕获。
         *
         * 这样做的好处：
         * - 用户快速连续点击"关闭"按钮时，第二次调用会直接返回 false
         * - 不会尝试对已移除的 View 再次调用 removeView
         */
        activeSession = null
        analysisUiState = AnalysisUiState.IDLE

        return try {
            // 使用 removeView 而非 removeViewImmediate：
            // 1. removeViewImmediate 是同步 IPC 调用，在 UI 线程上执行可能引起卡顿
            // 2. 当前所有调用者均绑定在主线程，无需立即同步移除
            // 3. removeView 将移除操作排队到主线程消息队列，不会阻塞调用线程
            session.manager.removeView(session.rootView)

            Log.i(TAG, "[Overlay] window removed. reason=$reason")
            true
        } catch (error: IllegalArgumentException) {
            // 窗口已经被系统移除（如用户切换应用时系统自动清理）
            Log.w(
                TAG,
                "[Overlay] window was already detached. reason=$reason",
                error,
            )
            false
        } catch (error: Exception) {
            // 其他未知异常
            Log.e(
                TAG,
                "[Overlay] unable to remove window. reason=$reason",
                error,
            )
            false
        }
    }

    /**
     * 绑定悬浮窗当前可用的透明度调节控件。
     *
     * 当前仅绑定透明度滑块（SeekBar），后续可扩展尺寸、圆角等控件。
     *
     * @param context 上下文，用于获取 SharedPreferences
     * @param view 悬浮窗根 View，用于查找 SeekBar 控件
     *
     * 功能：
     * - 监听 SeekBar 进度变化（仅在用户拖动时响应，程序设置进度时不响应）
     * - 实时更新 View 的 alpha 值
     * - 更新数值显示 TextView
     * - 将新值持久化到 SharedPreferences
     */
    private fun setupAdjustmentSliders(
        context: Context,
        view: View,
    ) {
        // 获取持久化存储实例
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // --- 透明度滑块 ---
        val alphaSlider = view.findViewById<SeekBar>(R.id.overlayAlphaSlider)
        val alphaValue = view.findViewById<TextView>(R.id.overlayAlphaValue)

        // 注册进度变化监听器
        alphaSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            /**
             * 进度变化回调。
             *
             * @param seekBar 触发事件的 SeekBar
             * @param progress 新的进度值（0 ~ 100）
             * @param fromUser 是否由用户操作引起（true=用户拖动，false=程序设置）
             *
             * 只有用户主动拖动时才执行更新，避免循环触发（如 restoreAdjustments
             * 设置进度时不应再次触发保存）。
             */
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // 程序设置的进度变化不处理
                if (!fromUser) return

                // 将进度值（0-100）转换为 alpha 值（0.0-1.0）
                val alpha = progress / 100f

                // 实时更新整个悬浮窗的透明度
                view.alpha = alpha

                // 更新数值显示文本（如 "85%"）
                alphaValue?.text = "$progress%"

                // 持久化保存到 SharedPreferences
                prefs.edit().putFloat(KEY_ALPHA, alpha).apply()
            }

            /** 用户开始拖动滑块时调用（当前无特殊处理） */
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            /** 用户停止拖动滑块时调用（当前无特殊处理） */
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /**
     * dp 转 px 的工具方法。
     *
     * 使用标准的密度转换公式：px = dp * density + 0.5f（四舍五入）。
     *
     * @param context 上下文，用于获取屏幕密度
     * @param value dp 值
     * @return 对应的 px 值
     */
    private fun dp(context: Context, value: Int): Int {
        return (
            value * context.resources.displayMetrics.density + 0.5f
        ).toInt()
    }

    /**
     * 将悬浮窗背景的圆角半径设置为指定值。
     *
     * 悬浮窗背景是 GradientDrawable，通过 mutate() 确保修改不会影响其他共享同一 Drawable 的 View。
     * 如果背景不是 GradientDrawable，记录 Warning 日志并跳过。
     *
     * @param view 悬浮窗根 View
     * @param radiusPx 圆角半径（单位：px）
     */
    private fun applyOverlayCornerRadius(
        view: View,
        radiusPx: Int,
    ) {
        // 尝试将背景转换为 GradientDrawable
        val drawable = view.background as? GradientDrawable

        if (drawable == null) {
            Log.w(
                TAG,
                "[Overlay] root background is not a GradientDrawable; corner radius update skipped.",
            )
            return
        }

        // mutate() 确保此 Drawable 的修改是独立的，不会影响其他共享此背景的 View
        drawable.mutate()
        // 设置圆角半径
        drawable.cornerRadius = radiusPx.toFloat()
    }
}
