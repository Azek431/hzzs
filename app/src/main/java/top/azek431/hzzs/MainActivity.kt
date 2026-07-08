// 火崽崽助手（HZZS）主 Activity。
//
// 这是应用的唯一入口页面，也是整个 UI 层的调度中心。
// 负责以下核心职责：
// 1. 初始化 Edge-to-Edge 全屏显示与状态栏/导航栏配色
// 2. 处理系统栏安全区域（Insets），避免内容被刘海屏/底部横条遮挡
// 3. 绑定首页按钮点击事件（开发计划弹窗、悬浮窗开关、免责声明入口）
// 4. 管理悬浮窗权限请求与授权引导
// 5. 绑定底部社区链接（QQ 群、Telegram 频道）
// 6. 通过缓存 View 引用避免反复 getIdentifier() 查找
// 7. 首次启动时检查免责声明是否已同意
// 8. 显示权限与设备状态卡片

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
import top.azek431.hzzs.model.RectF
import top.azek431.hzzs.service.OverlayNotificationService
import top.azek431.hzzs.CommunityLinks
import top.azek431.hzzs.ui.disclaimer.DisclaimerActivity
import top.azek431.hzzs.ui.overlay.OverlayPreviewManager
import top.azek431.hzzs.util.FeatureFlags

class MainActivity : AppCompatActivity() {

    // ==================== 缓存的 View 引用 ====================

    /** 根容器：CoordinatorLayout，整个页面的最外层布局 */
    private lateinit var rootContainer: View

    /** 顶部栏：LinearLayout，包含应用名称和副标题 */
    private lateinit var topBarContainer: View

    /** 滚动区域：NestedScrollView，承载首页所有内容 */
    private lateinit var homeScrollView: View

    /** 开发计划按钮：MaterialButton */
    private lateinit var btnDevelopmentPlan: MaterialButton

    /** 悬浮窗开关按钮：MaterialButton */
    private lateinit var btnOverlayExecution: MaterialButton

    /** 免责声明与功能设置按钮 */
    private lateinit var btnDisclaimer: MaterialButton

    /** 社区 QQ 群链接 TextView */
    private lateinit var textCommunityQqLink: TextView

    /** 社区 Telegram 链接 TextView */
    private lateinit var textCommunityTelegramLink: TextView

    // ==================== Padding 初始值缓存 ====================

    private var topBarPaddingStartInit = 0
    private var topBarPaddingTopInit = 0
    private var topBarPaddingEndInit = 0
    private var topBarPaddingBottomInit = 0

    private var scrollPaddingStartInit = 0
    private var scrollPaddingTopInit = 0
    private var scrollPaddingEndInit = 0
    private var scrollPaddingBottomInit = 0

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.enableEdgeToEdge(window)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContentView(R.layout.activity_main)
        cacheViews()
        cacheInitialPadding()
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
        refreshOverlayButton()
    }

    // ==================== View 缓存 ====================

    private fun cacheViews() {
        rootContainer = findViewById(R.id.rootContainer)
            ?: throw IllegalStateException("rootContainer not found in activity_main.xml")
        topBarContainer = findViewById(R.id.topBarContainer)
            ?: throw IllegalStateException("topBarContainer not found in activity_main.xml")
        homeScrollView = findViewById(R.id.homeScrollView)
            ?: throw IllegalStateException("homeScrollView not found in activity_main.xml")
        btnDevelopmentPlan = findViewById(R.id.btnDevelopmentPlan)
            ?: throw IllegalStateException("btnDevelopmentPlan not found in activity_main.xml")
        btnOverlayExecution = findViewById(R.id.btnOverlayExecution)
            ?: throw IllegalStateException("btnOverlayExecution not found in activity_main.xml")
        btnDisclaimer = findViewById(R.id.btnDisclaimer)
            ?: throw IllegalStateException("btnDisclaimer not found in activity_main.xml")
        textCommunityQqLink = findViewById(R.id.textCommunityQqLink)
            ?: throw IllegalStateException("textCommunityQqLink not found in activity_main.xml")
        textCommunityTelegramLink = findViewById(R.id.textCommunityTelegramLink)
            ?: throw IllegalStateException("textCommunityTelegramLink not found in activity_main.xml")
    }

    private fun cacheInitialPadding() {
        topBarPaddingStartInit = topBarContainer.paddingStart
        topBarPaddingTopInit = topBarContainer.paddingTop
        topBarPaddingEndInit = topBarContainer.paddingEnd
        topBarPaddingBottomInit = topBarContainer.paddingBottom

        scrollPaddingStartInit = homeScrollView.paddingStart
        scrollPaddingTopInit = homeScrollView.paddingTop
        scrollPaddingEndInit = homeScrollView.paddingEnd
        scrollPaddingBottomInit = homeScrollView.paddingBottom
    }

    // ==================== 事件绑定 ====================

    private fun bindHomeActions() {
        btnDevelopmentPlan.setOnClickListener {
            showDevelopmentPlan()
        }

        btnOverlayExecution.setOnClickListener {
            handleOverlayPreview()
        }

        btnDisclaimer.setOnClickListener {
            startActivity(Intent(this, DisclaimerActivity::class.java))
        }
    }

    private fun bindCommunityFooterLinks() {
        val fallbackMsg = getString(R.string.community_open_fallback)

        val linkBindings = mapOf(
            R.id.textCommunityQqLink to (textCommunityQqLink to R.string.community_qq_label),
            R.id.textCommunityTelegramLink to (textCommunityTelegramLink to R.string.community_telegram_label),
        )

        for ((resId, binding) in linkBindings) {
            val textView = findViewById<TextView>(resId) ?: continue
            val (_, labelRes) = binding
            val communityEntry = CommunityLinks.entries.find { it.labelRes == labelRes }
                ?: continue

            textView.setOnClickListener {
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
                    getString(R.string.overlay_preview_open_failed),
                ),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)
    }

    private fun showOverlayPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(
                stringOrFallback("overlay_permission_required_title", "需要悬浮窗权限"),
            )
            .setMessage(
                stringOrFallback(
                    "overlay_permission_required_message",
                    "火崽崽助手需要「显示在其他应用上层」的权限，才能显示悬浮窗预览。",
                ),
            )
            .setNegativeButton(
                stringOrFallback("action_close", "关闭"),
                null,
            )
            .setPositiveButton(
                stringOrFallback("overlay_permission_go_to_settings", "前往授权"),
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
                        getString(R.string.settings_open_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .show()
    }

    private fun refreshOverlayButton() {
        btnOverlayExecution.text = if (OverlayPreviewManager.isShowing()) {
            stringOrFallback("overlay_preview_close", "关闭悬浮窗")
        } else {
            stringOrFallback("overlay_preview_open", "打开悬浮窗")
        }
    }

    // ==================== 对话框 ====================

    private fun showDevelopmentPlan() {
        MaterialAlertDialogBuilder(this)
            .setTitle(
                stringOrFallback("development_plan_title", "开发计划"),
            )
            .setMessage(
                stringOrFallback(
                    "development_plan_message",
                    "1. 界面与导航\n2. 权限与设备检查\n3. 跑酷像素分析\n4. 实时 HUD 与本局战报\n5. 历史数据与校准",
                ),
            )
            .setPositiveButton(
                stringOrFallback("action_close", "关闭"),
                null,
            )
            .show()
    }

    // ==================== 系统栏适配 ====================

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )

            topBarContainer.updatePadding(
                left = topBarPaddingStartInit + safeInsets.left,
                top = topBarPaddingTopInit + safeInsets.top,
                right = topBarPaddingEndInit + safeInsets.right,
                bottom = topBarPaddingBottomInit,
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

    private fun stringOrFallback(name: String, fallback: String): String {
        val id = resources.getIdentifier(name, "string", packageName)
        return if (id == 0) fallback else getString(id)
    }
}
