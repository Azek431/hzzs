package top.azek431.hzzs

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 火崽崽助手 (HZZS) 主 Activity。
 *
 * 这是应用的唯一入口页面，负责：
 * - 初始化 Edge-to-Edge 全屏显示与状态栏/导航栏配色
 * - 处理系统栏安全区域（Insets），避免内容被刘海/底部横条遮挡
 * - 绑定首页按钮点击事件（开发计划弹窗、悬浮窗开关）
 * - 管理悬浮窗权限请求与授权引导
 * - 绑定底部社区链接（QQ 群、Telegram 频道）
 * - 通过反射式 findViewById 动态查找控件（避免硬编码 R.id 依赖）
 *
 * 当前处于早期开发阶段，核心分析引擎尚未接入，
 * 主要验证 UI 流程、悬浮窗面板与权限交互。
 */
class MainActivity : AppCompatActivity() {

    /**
     * 活动首次创建时的生命周期回调。
     *
     * 执行顺序：
     * 1. 启用 Edge-to-Edge 模式，使内容延伸到系统栏下方
     * 2. 设置状态栏和导航栏图标颜色为深色（适配浅色背景）
     * 3. 加载主布局 activity_main.xml
     * 4. 应用系统栏安全边距
     * 5. 绑定首页按钮点击事件
     * 6. 绑定底部社区链接点击事件
     * 7. 刷新悬浮窗按钮文本（根据悬浮窗当前状态）
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        // 调用父类 onCreate，恢复保存的实例状态
        super.onCreate(savedInstanceState)

        // 启用 Edge-to-Edge 模式：让应用内容延伸至屏幕边缘
        // 包括状态栏和导航栏下方，实现真正的沉浸式全屏效果
        WindowCompat.enableEdgeToEdge(window)

        // 配置状态栏和导航栏的图标颜色
        // isAppearanceLightStatusBars = false → 深色图标（白色文字/图标）
        // isAppearanceLightNavigationBars = false → 深色导航栏图标
        // 这与浅色背景主题 (surface_background = #FFF8F6) 形成对比
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // 加载主界面布局：activity_main.xml
        // 包含：顶部标题栏、功能卡片、开发计划按钮、悬浮窗开关按钮、底部社区链接
        setContentView(R.layout.activity_main)

        // 应用系统栏安全区域（状态栏高度、导航栏高度、刘海屏 cutout）
        // 确保顶部栏和滚动区域不被系统 UI 遮挡
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

    /**
     * 绑定首页功能按钮的点击事件。
     *
     * 通过字符串名称动态查找 View，避免在布局文件中写死 id 引用。
     * 当前绑定两个按钮：
     * - btnDevelopmentPlan → 弹出开发计划对话框
     * - btnOverlayExecution → 切换悬浮窗显示/隐藏
     */
    private fun bindHomeActions() {
        findViewByName("btnDevelopmentPlan")
            ?.setOnClickListener {
                showDevelopmentPlan()
            }

        findViewByName("btnOverlayExecution")
            ?.setOnClickListener {
                handleOverlayPreview()
            }
    }

    /**
     * 绑定底部社区链接的点击事件。
     *
     * 点击后调用 CommunityLinks.openLink() 尝试在浏览器中打开对应链接。
     * 如果设备上没有可以打开链接的应用（ActivityNotFoundException），
     * 则自动将链接复制到剪贴板并提示用户。
     *
     * 绑定的链接：
     * - textCommunityQqLink → HZZS QQ 交流群
     * - textCommunityTelegramLink → Azek431 Telegram 主频道
     */
    private fun bindCommunityFooterLinks() {
        findViewByName("textCommunityQqLink")
            ?.setOnClickListener {
                CommunityLinks.openLink(
                    context = this,
                    label = getString(R.string.community_qq_label),
                    url = CommunityLinks.HZZS_QQ_GROUP_URL,
                    fallbackMessage = getString(
                        R.string.community_open_fallback,
                    ),
                )
            }

        findViewByName("textCommunityTelegramLink")
            ?.setOnClickListener {
                CommunityLinks.openLink(
                    context = this,
                    label = getString(R.string.community_telegram_label),
                    url = CommunityLinks.AZEK_MAIN_TELEGRAM_URL,
                    fallbackMessage = getString(
                        R.string.community_open_fallback,
                    ),
                )
            }
    }

    /**
     * 处理悬浮窗预览面板的打开/关闭操作。
     *
     * 完整流程：
     * 1. 检查是否已获得 SYSTEM_ALERT_WINDOW（悬浮窗）权限
     *    → 未获得则弹出权限申请引导对话框
     * 2. 如果悬浮窗已在显示中，则调用 OverlayPreviewManager.hide() 关闭
     * 3. 如果悬浮窗未显示，则调用 OverlayPreviewManager.show() 打开
     * 4. 操作完成后刷新按钮文本
     * 5. 如果打开失败，弹出 Toast 提示用户检查授权状态
     */
    private fun handleOverlayPreview() {
        // 第一步：检查悬浮窗权限
        if (!hasOverlayPermission()) {
            showOverlayPermissionDialog()
            return
        }

        // 第二步：如果悬浮窗已在显示中，则关闭它
        if (OverlayPreviewManager.isShowing()) {
            OverlayPreviewManager.hide()
            refreshOverlayButton()
            return
        }

        // 第三步：打开悬浮窗面板
        val opened = OverlayPreviewManager.show(this)

        // 第四步：刷新按钮文本（"打开悬浮窗" ↔ "关闭悬浮窗"）
        refreshOverlayButton()

        // 第五步：如果打开失败，提示用户
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
     *
     * 判断逻辑：
     * - Android 6.0（API 23）以下：不需要此权限，直接返回 true
     * - Android 6.0 及以上：调用 Settings.canDrawOverlays() 查询系统授予的状态
     *
     * @return true 如果已有权限或系统版本低于 M
     */
    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)
    }

    /**
     * 显示悬浮窗权限申请对话框。
     *
     * 使用 MaterialAlertDialogBuilder 创建一个模态对话框，说明为什么需要此权限，
     * 并提供"前往授权"按钮跳转到系统设置页面（Settings.ACTION_MANAGE_OVERLAY_PERMISSION）。
     * 用户可以在系统设置中手动授予权限。
     *
     * 如果跳转系统设置页面失败（某些定制 ROM 可能路径不同），
     * 则捕获异常并提示用户手动前往设置授权。
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
                // 构造跳转到系统悬浮窗权限设置页面的 Intent
                // Uri 格式：package:当前应用包名
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )

                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    // 某些设备可能不支持此 Intent action，给出降级提示
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
     * 根据当前悬浮窗是否正在显示，动态切换按钮文字：
     * - 悬浮窗已显示 → "关闭悬浮窗"
     * - 悬浮窗未显示 → "打开悬浮窗"
     *
     * 使用 stringOrFallback() 确保即使在字符串资源缺失时也有合理的默认文本。
     */
    private fun refreshOverlayButton() {
        val button = findViewByName("btnOverlayExecution") as? MaterialButton ?: return

        button.text = if (OverlayPreviewManager.isShowing()) {
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
     *
     * 使用 MaterialAlertDialogBuilder 弹出一个模态对话框，
     * 展示当前应用的开发路线图，包括：
     * 1) 界面与导航
     * 2) 权限与设备检查
     * 3) 跑酷像素分析
     * 4) 实时 HUD 与本局战报
     * 5) 历史数据与校准
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

    /**
     * 应用系统栏安全区域（Edge-to-Edge Insets）。
     *
     * 在启用 Edge-to-Edge 模式后，状态栏和导航栏会覆盖在内容上方。
     * 此方法为顶部栏（topBarContainer）和滚动区域（homeScrollView）
     * 添加额外的 padding，确保内容不会被系统 UI 遮挡。
     *
     * 具体处理：
     * - 顶部栏：增加左、上、右三侧 padding（底部不需要，因为内容由 ScrollView 控制）
     * - 滚动区域：增加左、右、下三侧 padding（顶部不需要，因为顶部栏已经处理了上边距）
     * - 同时考虑 displayCutout（刘海屏/挖孔屏区域）
     *
     * 使用 ViewCompat.setOnApplyWindowInsetsListener 监听系统栏高度变化
     * （例如折叠屏展开/收起时），确保始终正确适配。
     */
    private fun applySystemBarInsets() {
        // 获取根容器、顶部栏和滚动区域的 View 引用
        val root = findViewByName("rootContainer") ?: return
        val topBar = findViewByName("topBarContainer")
        val scrollView = findViewByName("homeScrollView")

        // 保存当前的 padding 值，避免重复叠加
        val topBarPaddingStart = topBar?.paddingStart ?: 0
        val topBarPaddingTop = topBar?.paddingTop ?: 0
        val topBarPaddingEnd = topBar?.paddingEnd ?: 0

        val scrollPaddingStart = scrollView?.paddingStart ?: 0
        val scrollPaddingTop = scrollView?.paddingTop ?: 0
        val scrollPaddingEnd = scrollView?.paddingEnd ?: 0
        val scrollPaddingBottom = scrollView?.paddingBottom ?: 0

        // 注册系统栏 Insets 监听器
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            // 获取系统栏 + 刘海屏的安全区域
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )

            // 为顶部栏增加左/上/右 padding
            topBar?.updatePadding(
                left = topBarPaddingStart + safeInsets.left,
                top = topBarPaddingTop + safeInsets.top,
                right = topBarPaddingEnd + safeInsets.right,
            )

            // 为滚动区域增加左/右/下 padding（顶部不需要，由顶部栏处理）
            scrollView?.updatePadding(
                left = scrollPaddingStart + safeInsets.left,
                top = scrollPaddingTop,
                right = scrollPaddingEnd + safeInsets.right,
                bottom = scrollPaddingBottom + safeInsets.bottom,
            )

            insets
        }

        // 触发一次 Insets 计算，确保初始状态就正确
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * 通过 View 的名称（id 的资源名）动态查找 View。
     *
     * 使用 Resources.getIdentifier() 根据字符串名称查找资源 ID，
     * 然后调用 findViewById() 获取 View 实例。
     *
     * 这种方式的优势：
     * - 不需要在代码中硬编码 R.id.xxx 引用
     * - 方便在 XML 布局中通过 android:id="@+id/xxx" 定义后动态访问
     * - 便于后期维护和扩展新的控件
     *
     * @param name View 的 id 名称（如 "btnDevelopmentPlan"）
     * @return 找到的 View，如果不存在则返回 null
     */
    private fun findViewByName(name: String): View? {
        // 根据名称查找资源 ID
        val id = resources.getIdentifier(name, "id", packageName)

        return if (id == 0) {
            // 资源 ID 为 0 表示未找到对应的资源
            null
        } else {
            findViewById(id)
        }
    }

    /**
     * 安全地获取字符串资源，如果资源不存在则返回备用文本。
     *
     * 在开发过程中，字符串资源可能尚未添加或命名不一致。
     * 此方法确保即使资源缺失，UI 也能显示合理的默认文本，
     * 避免因 getString() 抛出 NotFoundException 而导致崩溃。
     *
     * @param name 字符串资源的名称（不含 "R.string." 前缀）
     * @param fallback 当资源不存在时返回的备用文本
     * @return 实际的字符串资源值，或 fallback
     */
    private fun stringOrFallback(name: String, fallback: String): String {
        // 根据名称查找字符串资源 ID
        val id = resources.getIdentifier(name, "string", packageName)

        return if (id == 0) {
            // 资源不存在，返回备用文本
            fallback
        } else {
            // 资源存在，返回实际的字符串
            getString(id)
        }
    }
}
