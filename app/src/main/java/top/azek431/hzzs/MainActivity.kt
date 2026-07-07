package top.azek431.hzzs

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 火崽崽助手（HZZS）主 Activity。
 *
 * 这是应用的唯一入口页面，也是整个 UI 层的调度中心。
 * 负责以下核心职责：
 * 1. 初始化 Edge-to-Edge 全屏显示与状态栏/导航栏配色
 * 2. 处理系统栏安全区域（Insets），避免内容被刘海屏/底部横条遮挡
 * 3. 绑定首页按钮点击事件（开发计划弹窗、悬浮窗开关）
 * 4. 管理悬浮窗权限请求与授权引导
 * 5. 绑定底部社区链接（QQ 群、Telegram 频道）
 * 6. 通过缓存 View 引用避免反复 getIdentifier() 查找
 *
 * 架构设计要点：
 * - 使用 lateinit var 缓存 View 引用，在 onCreate 中一次性初始化
 * - 使用 MaterialAlertDialogBuilder 替代 AlertDialog，保持 Material 3 风格
 * - 使用 CommunityLinkEntry 数据类实现社区链接的数据驱动绑定
 * - 使用 stringOrFallback() 确保字符串资源缺失时不崩溃
 *
 * 当前处于早期开发阶段，核心分析引擎（C++）尚未接入首页。
 * 主要验证 UI 流程、悬浮窗面板与权限交互。
 */
class MainActivity : AppCompatActivity() {

    // ==================== 缓存的 View 引用 ====================
    // 在 onCreate 中通过 findViewById(R.id.xxx) 一次性查找并缓存所有需要频繁访问的 View。
    //
    // 设计原因：
    // - 旧的 findViewByName() 使用 Resources.getIdentifier() 字符串查找，每次调用都需要
    //   解析字符串 → 查找资源表 → 返回 ID → findViewById，性能较差
    // - 改用 lateinit var + findViewById(R.id.xxx) 后，查找只在 onCreate 执行一次，
    //   后续所有操作直接使用缓存的引用，类型安全且零开销
    //
    // 注意：这些引用在 onCreate 中初始化，在 onDestroy 前始终有效。
    // 不使用 nullable 类型（如 View?）是为了避免每次使用时都解包。
    // 如果 findViewById 返回 null 会抛出 UninitializedPropertyAccessException，
    // 这在开发阶段更容易被发现（而不是运行时 NullPointerException）。

    /** 根容器：CoordinatorLayout，整个页面的最外层布局 */
    private lateinit var rootContainer: View

    /** 顶部栏：LinearLayout，包含应用名称和副标题 */
    private lateinit var topBarContainer: View

    /** 滚动区域：NestedScrollView，承载首页所有内容（功能卡片、社区链接等） */
    private lateinit var homeScrollView: View

    /** 开发计划按钮：MaterialButton，点击后弹出开发计划对话框 */
    private lateinit var btnDevelopmentPlan: MaterialButton

    /** 悬浮窗开关按钮：MaterialButton，点击后打开/关闭悬浮窗预览面板 */
    private lateinit var btnOverlayExecution: MaterialButton

    /** 社区 QQ 群链接 TextView：位于 view_community_footer.xml 中，点击后打开 QQ 群链接 */
    private lateinit var textCommunityQqLink: TextView

    /** 社区 Telegram 链接 TextView：位于 view_community_footer.xml 中，点击后打开 Telegram 频道 */
    private lateinit var textCommunityTelegramLink: TextView

    /** 悬浮窗面板根容器（在悬浮窗布局中） */
    private lateinit var overlayContentPanel: View

    // ==================== Padding 初始值缓存 ====================
    // 用于防止 applySystemBarInsets 在折叠屏等设备上重复叠加 padding。
    //
    // 问题背景：
    // - Edge-to-Edge 模式下，系统栏（状态栏/导航栏）会覆盖在内容上方
    // - applySystemBarInsets() 通过 ViewCompat.setOnApplyWindowInsetsListener 监听系统栏高度变化
    // - 折叠屏展开/收起时，系统栏高度会变，listener 会被多次触发
    // - 如果每次触发都在当前 padding 基础上叠加，padding 会无限增长
    //
    // 解决方案：
    // - 在 onCreate 中缓存初始 padding 值
    // - 每次 listener 触发时，使用"初始值 + 安全区域"计算新 padding
    // - 这样无论 listener 触发多少次，padding 始终是初始值 + 当前系统栏高度，不会叠加
    private var topBarPaddingStartInit = 0
    private var topBarPaddingTopInit = 0
    private var topBarPaddingEndInit = 0

    private var scrollPaddingStartInit = 0
    private var scrollPaddingTopInit = 0
    private var scrollPaddingEndInit = 0
    private var scrollPaddingBottomInit = 0

    // ==================== 生命周期 ====================

    /**
     * 活动首次创建时的生命周期回调。
     *
     * 执行顺序（严格按照以下顺序，因为后续步骤依赖前置步骤的结果）：
     * 1. super.onCreate(savedInstanceState) — 恢复保存的实例状态（如配置变更后的重建）
     * 2. WindowCompat.enableEdgeToEdge(window) — 启用 Edge-to-Edge 全屏模式
     * 3. 配置状态栏/导航栏图标颜色为深色 — 适配浅色背景主题
     * 4. setContentView(R.layout.activity_main) — 加载主布局
     * 5. cacheViews() — 一次性查找并缓存所有 View 引用（替代 getIdentifier 查找）
     * 6. cacheInitialPadding() — 缓存 padding 初始值，防止折叠屏重复叠加
     * 7. applySystemBarInsets() — 注册系统栏 Insets 监听器，应用安全区域
     * 8. bindHomeActions() — 绑定首页按钮点击事件
     * 9. bindCommunityFooterLinks() — 绑定底部社区链接点击事件
     * 10. refreshOverlayButton() — 根据悬浮窗当前状态刷新按钮文本
     *
     * 注意：savedInstanceState 为 null 表示首次创建，非 null 表示从后台恢复或配置变更重建。
     * 由于我们使用 lateinit var 缓存 View，重建时会重新初始化（不会保留旧引用）。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        // 调用父类 onCreate 恢复保存的实例状态
        // 使用 savedInstanceState 而非 this@MainActivity：
        // savedInstanceState 是框架传入的正确 Bundle，this@MainActivity 是误写（原 commit 中的 bug）
        super.onCreate(savedInstanceState)

        // 启用 Edge-to-Edge 模式：让应用内容延伸至屏幕边缘
        // 包括状态栏和导航栏下方，实现真正的沉浸式全屏效果
        // 不调用此方法时，系统栏会遮挡部分内容
        WindowCompat.enableEdgeToEdge(window)

        // 配置状态栏和导航栏的图标颜色为深色
        // isAppearanceLightStatusBars = false → 状态栏图标为深色（白色）
        // isAppearanceLightNavigationBars = false → 导航栏图标为深色（白色）
        // 这是因为我们的背景是浅色 (#FFF8F6)，深色图标才有足够对比度
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // 加载主界面布局：activity_main.xml
        // 包含：顶部标题栏、功能卡片、开发计划按钮、悬浮窗开关按钮、底部社区链接
        setContentView(R.layout.activity_main)

        // 一次性查找并缓存所有需要频繁访问的 View 引用
        // 替代原来的 findViewByName() 反射式查找，性能更好且类型安全
        cacheViews()

        // 缓存 padding 初始值，用于 applySystemBarInsets 中防止折叠屏重复叠加
        cacheInitialPadding()

        // 注册系统栏 Insets 监听器，应用安全区域
        // 确保内容不会被状态栏/导航栏/刘海屏遮挡
        applySystemBarInsets()

        // 绑定首页功能按钮（开发计划、悬浮窗开关）的点击事件
        bindHomeActions()

        // 绑定底部社区链接（QQ 群、Telegram）的点击事件
        bindCommunityFooterLinks()

        // 根据悬浮窗当前是否可见，刷新"打开/关闭悬浮窗"按钮的文本
        refreshOverlayButton()
    }

    /**
     * 每次活动回到前台时调用（例如从设置页面返回）。
     *
     * 为什么需要在 onResume 中刷新按钮文本？
     * - 用户可能从系统设置页面手动开启了悬浮窗
     * - 回到主页后，按钮应显示"关闭悬浮窗"而非"打开悬浮窗"
     * - 如果不调用 refreshOverlayButton()，按钮文本会与实际情况不一致
     *
     * 注意：不在 onPause 中刷新是因为悬浮窗关闭后按钮文本由 hide() 内部处理。
     */
    override fun onResume() {
        super.onResume()
        refreshOverlayButton()
    }

    // ==================== View 缓存 ====================

    /**
     * 一次性查找并缓存所有需要频繁访问的 View 引用。
     *
     * 替代原来的 findViewByName() 反射式查找，
     * 使用 findViewById(R.id.xxx) 直接获取，性能更好且类型安全。
     *
     * 缓存的 View 包括：
     * - rootContainer：CoordinatorLayout 根容器（用于系统栏 Insets 监听）
     * - topBarContainer：顶部栏（用于系统栏 Insets padding 调整）
     * - homeScrollView：滚动区域（用于系统栏 Insets padding 调整）
     * - btnDevelopmentPlan：开发计划按钮
     * - btnOverlayExecution：悬浮窗开关按钮
     * - textCommunityQqLink / textCommunityTelegramLink：底部社区链接
     *
     * 注意：此方法在 setContentView() 之后调用，确保布局已加载。
     */
    private fun cacheViews() {
        rootContainer = findViewById(R.id.rootContainer)
        topBarContainer = findViewById(R.id.topBarContainer)
        homeScrollView = findViewById(R.id.homeScrollView)
        btnDevelopmentPlan = findViewById(R.id.btnDevelopmentPlan)
        btnOverlayExecution = findViewById(R.id.btnOverlayExecution)
        textCommunityQqLink = findViewById(R.id.textCommunityQqLink)
        textCommunityTelegramLink = findViewById(R.id.textCommunityTelegramLink)
    }

    /**
     * 缓存 padding 初始值，用于 applySystemBarInsets 中防止重复叠加。
     *
     * 折叠屏等设备在展开/收起时，系统栏高度会变，
     * 如果每次都基于当前 padding 叠加，padding 会无限增长。
     * 使用初始值可以保证无论 listener 触发多少次，
     * padding 始终是"初始值 + 当前系统栏高度"。
     */
    private fun cacheInitialPadding() {
        topBarPaddingStartInit = topBarContainer.paddingStart
        topBarPaddingTopInit = topBarContainer.paddingTop
        topBarPaddingEndInit = topBarContainer.paddingEnd

        scrollPaddingStartInit = homeScrollView.paddingStart
        scrollPaddingTopInit = homeScrollView.paddingTop
        scrollPaddingEndInit = homeScrollView.paddingEnd
        scrollPaddingBottomInit = homeScrollView.paddingBottom
    }

    // ==================== 事件绑定 ====================

    /**
     * 绑定首页功能按钮的点击事件。
     *
     * 使用缓存的 View 引用，不再需要 findViewByName()。
     * 绑定的按钮：
     * - btnDevelopmentPlan → showDevelopmentPlan()：弹出开发计划对话框
     * - btnOverlayExecution → handleOverlayPreview()：打开/关闭悬浮窗
     */
    private fun bindHomeActions() {
        btnDevelopmentPlan.setOnClickListener {
            showDevelopmentPlan()
        }

        btnOverlayExecution.setOnClickListener {
            handleOverlayPreview()
        }
    }

    /**
     * 绑定底部社区链接的点击事件。
     *
     * 使用数据驱动方式：将 View、标签资源 ID 和 URL 打包为 CommunityLinkEntry，
     * 通过 for 循环统一绑定点击事件，避免重复代码。
     *
     * 点击后的行为：
     * 1. 尝试在系统浏览器中打开链接
     * 2. 如果设备上没有浏览器，自动将链接复制到剪贴板
     * 3. 显示 Toast 提示用户
     */
    private fun bindCommunityFooterLinks() {
        // 复用同一个 fallback 消息，避免多次 getString() 调用
        val fallbackMsg = getString(R.string.community_open_fallback)

        // 将社区链接配置为数据列表，便于扩展和维护
        // 新增链接只需在此列表中添加一项，无需修改绑定逻辑
        val links = listOf(
            CommunityLinkEntry(
                view = textCommunityQqLink,
                labelRes = R.string.community_qq_label,
                url = CommunityLinks.HZZS_QQ_GROUP_URL,
            ),
            CommunityLinkEntry(
                view = textCommunityTelegramLink,
                labelRes = R.string.community_telegram_label,
                url = CommunityLinks.AZEK_MAIN_TELEGRAM_URL,
            ),
        )

        // 遍历数据列表，为每个链接 View 绑定点击事件
        for (entry in links) {
            entry.view.setOnClickListener {
                CommunityLinks.openLink(
                    context = applicationContext, // 使用 ApplicationContext 避免内存泄漏
                    label = getString(entry.labelRes),
                    url = entry.url,
                    fallbackMessage = fallbackMsg,
                )
            }
        }
    }

    /**
     * 社区链接数据条目，用于 [bindCommunityFooterLinks] 的数据驱动绑定。
     *
     * 使用 data class 而非普通 class，因为我们需要：
     * 1. 简洁的构造函数（一行初始化三个字段）
     * 2. 自动生成 equals/hashCode/toString（便于调试）
     * 3. 支持解构声明（for 循环中不需要）
     *
     * @property view 要绑定的 TextView 实例（已从 cacheViews 缓存）
     * @property labelRes 链接标签的字符串资源 ID（用于剪贴板标识和日志）
     * @property url 要打开的完整 URL
     */
    private data class CommunityLinkEntry(
        val view: TextView,
        val labelRes: Int,
        val url: String,
    )

    // ==================== 悬浮窗管理 ====================

    /**
     * 处理悬浮窗预览面板的打开/关闭操作。
     */
    private fun handleOverlayPreview() {
        if (!hasOverlayPermission()) {
            showOverlayPermissionDialog()
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
            Toast.makeText(
                this,
                stringOrFallback(
                    "overlay_preview_open_failed",
                    "悬浮窗未能打开，请检查授权状态。",
                ),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * 检查当前应用是否已获得悬浮窗权限。
     */
    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)
    }

    /**
     * 显示悬浮窗权限申请对话框。
     */
    private fun showOverlayPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(
                stringOrFallback(
                    "overlay_permission_required_title",
                    "需要悬浮窗权限",
                ),
            )
            .setMessage(
                stringOrFallback(
                    "overlay_permission_required_message",
                    "火崽崽助手需要「显示在其他应用上层」的权限，才能显示悬浮窗预览。",
                ),
            )
            .setNegativeButton(
                stringOrFallback(
                    "action_close",
                    "关闭",
                ),
                null,
            )
            .setPositiveButton(
                stringOrFallback(
                    "overlay_permission_go_to_settings",
                    "前往授权",
                ),
            ) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )

                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "无法打开系统授权页面，请前往系统设置手动授权。",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .show()
    }

    /**
     * 刷新悬浮窗按钮的文本内容。
     *
     * 使用缓存的 btnOverlayExecution 引用，不再需要 findViewByName()。
     */
    private fun refreshOverlayButton() {
        btnOverlayExecution.text = if (OverlayPreviewManager.isShowing()) {
            stringOrFallback(
                "overlay_preview_close",
                "关闭悬浮窗",
            )
        } else {
            stringOrFallback(
                "overlay_preview_open",
                "打开悬浮窗",
            )
        }
    }

    /**
     * 显示开发计划对话框。
     */
    private fun showDevelopmentPlan() {
        MaterialAlertDialogBuilder(this)
            .setTitle(
                stringOrFallback(
                    "development_plan_title",
                    "开发计划",
                ),
            )
            .setMessage(
                stringOrFallback(
                    "development_plan_message",
                    "1）界面与导航\n\n2）权限与设备检查\n\n3）跑酷像素分析\n\n4）实时 HUD 与本局战报\n\n5）历史数据与校准",
                ),
            )
            .setPositiveButton(
                stringOrFallback(
                    "action_close",
                    "关闭",
                ),
                null,
            )
            .show()
    }

    // ==================== 系统栏适配 ====================

    /**
     * 应用系统栏安全区域（Edge-to-Edge Insets）。
     *
     * 使用缓存的 padding 初始值，防止折叠屏等设备上重复叠加。
     */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )

            // 使用初始 padding 值叠加，避免重复叠加
            topBarContainer.updatePadding(
                left = topBarPaddingStartInit + safeInsets.left,
                top = topBarPaddingTopInit + safeInsets.top,
                right = topBarPaddingEndInit + safeInsets.right,
                bottom = topBarPaddingEndInit, // 底部不需要 insets padding
            )

            homeScrollView.updatePadding(
                left = scrollPaddingStartInit + safeInsets.left,
                top = scrollPaddingTopInit,
                right = scrollPaddingEndInit + safeInsets.right,
                bottom = scrollPaddingBottomInit + safeInsets.bottom,
            )

            insets
        }

        ViewCompat.requestApplyInsets(rootContainer)
    }

    // ==================== 工具方法 ====================

    /**
     * 安全地获取字符串资源，如果资源不存在则返回备用文本。
     *
     * 在开发过程中，字符串资源可能尚未添加或命名不一致。
     * 此方法确保即使资源缺失，UI 也能显示合理的默认文本。
     *
     * @param name 字符串资源的名称（不含 "R.string." 前缀）
     * @param fallback 当资源不存在时返回的备用文本
     */
    private fun stringOrFallback(name: String, fallback: String): String {
        val id = resources.getIdentifier(name, "string", packageName)

        return if (id == 0) {
            fallback
        } else {
            getString(id)
        }
    }
}
