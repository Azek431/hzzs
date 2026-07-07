package top.azek431.hzzs

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.MaterialButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton as MaterialButtonWidget

/**
 * 火崽崽助手 (HZZS) 主 Activity。
 *
 * 这是应用的唯一入口页面，负责：
 * - 初始化 Edge-to-Edge 全屏显示与状态栏/导航栏配色
 * - 处理系统栏安全区域（Insets），避免内容被刘海/底部横条遮挡
 * - 绑定首页按钮点击事件（开发计划弹窗、悬浮窗开关）
 * - 管理悬浮窗权限请求与授权引导
 * - 绑定底部社区链接（QQ 群、Telegram 频道）
 *
 * View 引用在 onCreate 中一次性缓存，避免反复使用 getIdentifier() 查找。
 */
class MainActivity : AppCompatActivity() {

    // ==================== 缓存的 View 引用 ====================
    // 在 onCreate 中通过 findViewById(R.id.xxx) 一次性查找并缓存，
    // 避免后续每次调用都执行 getIdentifier() 字符串查找。

    /** 根容器：CoordinatorLayout */
    private lateinit var rootContainer: View

    /** 顶部栏：LinearLayout */
    private lateinit var topBarContainer: View

    /** 滚动区域：NestedScrollView */
    private lateinit var homeScrollView: View

    /** 开发计划按钮 */
    private lateinit var btnDevelopmentPlan: MaterialButtonWidget

    /** 悬浮窗开关按钮 */
    private lateinit var btnOverlayExecution: MaterialButtonWidget

    /** 社区 QQ 群链接 TextView（位于 view_community_footer.xml 中） */
    private lateinit var textCommunityQqLink: TextView

    /** 社区 Telegram 链接 TextView（位于 view_community_footer.xml 中） */
    private lateinit var textCommunityTelegramLink: TextView

    /** 悬浮窗面板根容器（在悬浮窗布局中） */
    private lateinit var overlayContentPanel: View

    // ==================== Padding 初始值缓存 ====================
    // 用于防止 applySystemBarInsets 在折叠屏等设备上重复叠加 padding。

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
     * 执行顺序：
     * 1. 启用 Edge-to-Edge 模式，使内容延伸到系统栏下方
     * 2. 设置状态栏和导航栏图标颜色为深色（适配浅色背景）
     * 3. 加载主布局 activity_main.xml
     * 4. 缓存所有 View 引用（避免后续反复 getIdentifier 查找）
     * 5. 缓存 padding 初始值
     * 6. 应用系统栏安全边距
     * 7. 绑定按钮点击事件
     * 8. 刷新悬浮窗按钮文本（根据悬浮窗当前状态）
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(this@MainActivity)

        // 启用 Edge-to-Edge 模式：让应用内容延伸至屏幕边缘
        WindowCompat.enableEdgeToEdge(window)

        // 配置状态栏和导航栏的图标颜色
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // 加载主界面布局
        setContentView(R.layout.activity_main)

        // 缓存所有 View 引用（一次性查找，后续直接复用）
        cacheViews()

        // 缓存 padding 初始值，防止重复叠加
        cacheInitialPadding()

        // 应用系统栏安全区域
        applySystemBarInsets()

        // 绑定首页功能按钮的点击事件
        bindHomeActions()

        // 绑定底部社区链接的点击事件
        bindCommunityFooterLinks()

        // 根据悬浮窗当前是否可见，刷新"打开/关闭悬浮窗"按钮的文本
        refreshOverlayButton()
    }

    /**
     * 每次活动回到前台时调用（例如从设置页面返回）。
     *
     * 用于刷新悬浮窗按钮文本——如果用户在设置页面手动打开了悬浮窗，
     * 回到主页后按钮应显示"关闭悬浮窗"而非"打开悬浮窗"。
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
     * 使用缓存的 View 引用，不再需要 findViewByName()。
     */
    private fun bindCommunityFooterLinks() {
        val fallbackMsg = getString(R.string.community_open_fallback)

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

        for (entry in links) {
            entry.view.setOnClickListener {
                CommunityLinks.openLink(
                    context = applicationContext,
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
     * @param view 要绑定的 TextView 实例（已从 cacheViews 缓存）
     * @param labelRes 链接标签的字符串资源 ID
     * @param url 要打开的完整 URL
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
