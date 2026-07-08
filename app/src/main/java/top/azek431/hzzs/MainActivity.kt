// 火崽崽助手（HZZS）主 Activity。
//
// 这是应用的唯一入口页面，也是整个 UI 层的调度中心。
//
// 职责（极简）：
// 1. 页面生命周期管理（onCreate / onResume）
// 2. 初始化所有 Controller（Insets、按钮、对话框、权限、View 缓存）
// 3. 调度业务逻辑（悬浮窗显示/隐藏、免责声明检查）
// 4. 绑定社区链接（QQ 群、Telegram 频道）
//
// 不负责：
// - 不直接处理系统栏安全区域（由 MainInsetsController 处理）
// - 不直接绑定按钮点击事件（由 MainActionBinder 处理）
// - 不直接显示对话框（由 MainDialogController 处理）
// - 不直接检查悬浮窗权限（由 OverlayPermissionController 处理）
// - 不直接管理悬浮窗生命周期（由 OverlayPreviewManager 处理）
// - 不缓存 View 引用（由 MainViewCache 处理）
// - 不缓存 Padding 初始值（由 MainInsetCache 处理）
//
// 设计原因：
// - MainActivity 只保留"组装和调度"的职责，每个 Controller 负责自己的领域
// - 新增功能时，只需添加/修改对应的 Controller，不碰 MainActivity
// - 符合单一职责原则和依赖倒置原则

package top.azek431.hzzs

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import top.azek431.hzzs.service.OverlayNotificationService
import top.azek431.hzzs.ui.community.CommunityLinks
import top.azek431.hzzs.ui.disclaimer.DisclaimerActivity
import top.azek431.hzzs.ui.main.MainActionBinder
import top.azek431.hzzs.ui.main.MainActionCallbacks
import top.azek431.hzzs.ui.main.MainDialogController
import top.azek431.hzzs.ui.main.MainInsetCache
import top.azek431.hzzs.ui.main.MainInsetsController
import top.azek431.hzzs.ui.main.MainViewCache
import top.azek431.hzzs.ui.main.MainViewCacheResult
import top.azek431.hzzs.ui.main.OverlayPermissionController
import top.azek431.hzzs.ui.overlay.OverlayPreviewManager
import top.azek431.hzzs.util.FeatureFlags

class MainActivity : AppCompatActivity(), MainActionCallbacks {

    // ==================== View 引用缓存结果 ====================

    /** 所有缓存 View 引用的集合（不可变） */
    private lateinit var views: MainViewCacheResult

    // ==================== Padding 初始值缓存 ====================

    /** Padding 初始值缓存器 */
    private val insetCache = MainInsetCache()

    // ==================== Controller 实例 ====================

    /** 系统栏安全区域控制器 */
    private lateinit var insetsController: MainInsetsController

    /** 按钮点击事件绑定器 */
    private lateinit var actionBinder: MainActionBinder

    /** 对话框控制器 */
    private lateinit var dialogController: MainDialogController

    /** 悬浮窗权限控制器 */
    private lateinit var permissionController: OverlayPermissionController

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启用 Edge-to-Edge 全屏显示
        WindowCompat.enableEdgeToEdge(window)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContentView(R.layout.activity_main)

        // 缓存所有 View 引用
        views = MainViewCache(this).retrieve()

        // 缓存 Padding 初始值
        insetCache.capture(views)

        // 初始化所有 Controller
        initControllers()

        // 执行页面初始化流程
        applySystemBarInsets()
        bindHomeActions()
        bindCommunityFooterLinks()
        refreshOverlayButton()

        // 检查免责声明是否已同意
        if (!FeatureFlags.isDisclaimerAccepted(this)) {
            startActivity(Intent(this, DisclaimerActivity::class.java).apply {
                putExtra(DisclaimerActivity.EXTRA_RETURN_TO_MAIN, true)
            })
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回到前台时刷新悬浮窗按钮状态
        refreshOverlayButton()
    }

    // ==================== Controller 初始化 ====================

    /**
     * 初始化所有 Controller。
     *
     * 在 View 缓存完成后调用，确保 Controller 能获取到有效的 View 引用。
     */
    private fun initControllers() {
        insetsController = MainInsetsController(
            rootContainer = views.rootContainer,
            topBarContainer = views.topBarContainer,
            homeScrollView = views.homeScrollView,
            topBarPaddingStartInit = insetCache.topBarPaddingStartInit,
            topBarPaddingTopInit = insetCache.topBarPaddingTopInit,
            topBarPaddingEndInit = insetCache.topBarPaddingEndInit,
            topBarPaddingBottomInit = insetCache.topBarPaddingBottomInit,
            scrollPaddingStartInit = insetCache.scrollPaddingStartInit,
            scrollPaddingTopInit = insetCache.scrollPaddingTopInit,
            scrollPaddingEndInit = insetCache.scrollPaddingEndInit,
            scrollPaddingBottomInit = insetCache.scrollPaddingBottomInit,
        )

        dialogController = MainDialogController(this)
        permissionController = OverlayPermissionController(this)

        actionBinder = MainActionBinder(
            btnDevelopmentPlan = views.btnDevelopmentPlan,
            btnOverlayExecution = views.btnOverlayExecution,
            btnDisclaimer = views.btnDisclaimer,
            callbacks = this,
        )
    }

    // ==================== 页面初始化流程 ====================

    /**
     * 应用系统栏安全区域。
     *
     * 委托给 MainInsetsController 处理，不直接在 MainActivity 中实现。
     */
    private fun applySystemBarInsets() {
        insetsController.apply()
    }

    /**
     * 绑定首页按钮点击事件。
     *
     * 委托给 MainActionBinder 处理，不直接在 MainActivity 中实现。
     */
    private fun bindHomeActions() {
        actionBinder.bind()
    }

    /**
     * 绑定底部社区链接。
     *
     * 社区链接的打开逻辑由 CommunityLinks 处理，这里只负责找到对应的 TextView 并注册点击事件。
     */
    private fun bindCommunityFooterLinks() {
        val fallbackMsg = getString(R.string.community_open_fallback)

        // 构建链接绑定映射：View ID → (TextView, 标签资源 ID)
        val linkBindings: Map<Int, Pair<TextView, Int>> = mapOf(
            R.id.textCommunityQqLink to Pair(views.textCommunityQqLink, R.string.community_qq_label),
            R.id.textCommunityTelegramLink to Pair(views.textCommunityTelegramLink, R.string.community_telegram_label),
        )

        for ((resId, binding) in linkBindings) {
            val (_, labelRes) = binding
            val communityEntry = CommunityLinks.entries.find { it.labelRes == labelRes }
                ?: continue

            binding.first.setOnClickListener {
                CommunityLinks.openLink(
                    context = applicationContext,
                    label = getString(communityEntry.labelRes),
                    url = communityEntry.url,
                    fallbackMessage = fallbackMsg,
                )
            }
        }
    }

    // ==================== 悬浮窗管理 ====================

    /**
     * 刷新悬浮窗按钮文本。
     *
     * 根据悬浮窗当前显示状态切换按钮文字：
     * - 显示中 → "关闭悬浮窗"
     * - 未显示 → "打开悬浮窗"
     */
    private fun refreshOverlayButton() {
        actionBinder.updateOverlayButtonText(OverlayPreviewManager.isShowing())
    }

    // ==================== MainActionCallbacks 实现 ====================

    /** 点击了"查看开发计划"按钮 */
    override fun onDevelopmentPlanClicked() {
        dialogController.showDevelopmentPlan()
    }

    /** 点击了"悬浮窗开关"按钮 */
    override fun onOverlayToggleClicked() {
        handleOverlayPreview()
    }

    /** 点击了"免责声明"按钮 */
    override fun onDisclaimerClicked() {
        startActivity(Intent(this, DisclaimerActivity::class.java))
    }

    /**
     * 处理悬浮窗开关逻辑。
     *
     * 流程：
     * 1. 检查悬浮窗权限 → 无权限则引导用户授权
     * 2. 悬浮窗已显示 → 关闭
     * 3. 悬浮窗未显示 → 打开
     *
     * 权限检查和跳转委托给 OverlayPermissionController，
     * 悬浮窗的显示/隐藏委托给 OverlayPreviewManager。
     */
    private fun handleOverlayPreview() {
        if (!permissionController.hasPermission()) {
            dialogController.showOverlayPermissionExplanation(
                onGoToSettings = { permissionController.openSettings() },
                onCancel = {},
            )
            return
        }

        if (OverlayPreviewManager.isShowing()) {
            OverlayPreviewManager.hide()
            refreshOverlayButton()
            return
        }

        val opened = OverlayPreviewManager.show(this)
        refreshOverlayButton()

        if (!opened) {
            android.widget.Toast.makeText(
                this,
                stringOrFallback(
                    "overlay_preview_open_failed",
                    getString(R.string.overlay_preview_open_failed),
                ),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * 获取资源字符串，如果资源不存在则返回提供的默认值。
     *
     * 用于未来功能预留：当新字符串资源尚未加入 strings.xml 时，
     * 不会导致 getString(id) 抛出 NotFoundException，而是优雅降级到 fallback。
     *
     * @param name 字符串资源名称（如 "overlay_preview_open"）
     * @param fallback 资源不存在时的回退文本
     * @return 实际字符串或 fallback
     */
    private fun stringOrFallback(name: String, fallback: String): String {
        val id = resources.getIdentifier(name, "string", packageName)
        return if (id == 0) fallback else getString(id)
    }
}
