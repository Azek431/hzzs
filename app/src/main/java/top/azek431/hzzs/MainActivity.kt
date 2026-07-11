// 火崽崽助手（HZZS）主 Activity。
//
// 这是应用的唯一入口页面，也是整个 UI 层的调度中心。
//
// 职责（极简）：
// 1. 页面生命周期管理（onCreate / onResume）
// 2. 底部导航栏管理（首页 / 设置 Fragment 切换）
// 3. 调度业务逻辑（悬浮窗显示/隐藏、免责声明检查）
// 4. 绑定社区链接（QQ 群、Telegram 频道）
//
// 设计原因：
// - MainActivity 只保留"组装和调度"的职责
// - 首页和设置页作为 Fragment 共存，切换时保留状态
// - 符合单一职责原则和依赖倒置原则

package top.azek431.hzzs

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import top.azek431.hzzs.ui.disclaimer.DisclaimerActivity
import top.azek431.hzzs.ui.home.HomeActionCallbacks
import top.azek431.hzzs.ui.home.HomeFragment
import top.azek431.hzzs.ui.main.MainDialogController
import top.azek431.hzzs.ui.main.OverlayPermissionController
import top.azek431.hzzs.ui.overlay.OverlayPreviewManager
import top.azek431.hzzs.ui.settings.SettingsFragmentPage
import top.azek431.hzzs.ui.settings.VisionSettingsKeys
import top.azek431.hzzs.core.util.FeatureFlags
import top.azek431.hzzs.core.util.Logger

/**
 * 主 Activity，同时也是 HomeActionCallbacks 的实现者。
 *
 * HomeFragment 通过 HomeActionCallbacks 接口调用业务方法，
 * MainActivity 实现该接口完成回调。
 */
class MainActivity : AppCompatActivity(), HomeActionCallbacks {

    // ==================== Controller 实例 ====================

    /** 对话框控制器 */
    private lateinit var dialogController: MainDialogController

    /** 悬浮窗权限控制器 */
    private lateinit var permissionController: OverlayPermissionController

    // ==================== 底部导航栏 ====================

    /** 首页 Fragment */
    private lateinit var homeFragment: HomeFragment

    /** 设置 Fragment */
    private lateinit var settingsFragment: SettingsFragmentPage

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

        // 检查免责声明（首次启动阻塞模式）：
        // 如果用户未同意声明，直接进入 DisclaimerActivity 并 finish() 自身，
        // 用户必须阅读并同意后才能进入应用。
        if (!FeatureFlags.isDisclaimerAccepted(this)) {
            startActivity(Intent(this, DisclaimerActivity::class.java).apply {
                putExtra(DisclaimerActivity.EXTRA_RETURN_TO_MAIN, false)
            })
            finish()
            return
        }

        // 初始化 Controller
        dialogController = MainDialogController(this)
        permissionController = OverlayPermissionController(this)

        // 初始化日志缓冲区容量（从 SharedPreferences 读取配置）
        val capacity = getSharedPreferences(VisionSettingsKeys.PREFS_NAME, MODE_PRIVATE)
            .getInt(VisionSettingsKeys.KEY_LOG_BUFFER_CAPACITY, VisionSettingsKeys.DEFAULT_LOG_BUFFER_CAPACITY)
        Logger.setCapacity(capacity)

        // 执行页面初始化流程
        refreshOverlayButton()
        setupBottomNavigation()
    }

    /**
     * 每次回到前台时刷新悬浮窗按钮状态。
     */
    override fun onResume() {
        super.onResume()
        refreshOverlayButton()
    }

    // ==================== 页面初始化流程 ====================

    // ==================== 底部导航栏 ====================

    /**
     * 设置底部导航栏。
     *
     * 创建首页和设置 Fragment，注册导航栏切换监听。
     * 默认显示首页。
     */
    private fun setupBottomNavigation() {
        homeFragment = HomeFragment.newInstance(MainActivity::class.java)
        settingsFragment = SettingsFragmentPage()

        // 默认显示首页
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, homeFragment, "home")
            .add(R.id.fragmentContainer, settingsFragment, "settings")
            .hide(settingsFragment)
            .commit()

        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    supportFragmentManager.beginTransaction()
                        .hide(settingsFragment)
                        .show(homeFragment)
                        .commit()
                    true
                }
                R.id.nav_settings -> {
                    supportFragmentManager.beginTransaction()
                        .hide(homeFragment)
                        .show(settingsFragment)
                        .commit()
                    true
                }
                else -> false
            }
        }
    }

    // ==================== 悬浮窗管理 ====================

    private fun refreshOverlayButton() {
        // 悬浮窗按钮在 HomeFragment 内，通过主页面更新
        val homeFrag = supportFragmentManager.findFragmentByTag("home") as? HomeFragment
        homeFrag?.let {
            val btn = it.requireView().findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOverlayExecution)
            btn?.text = if (OverlayPreviewManager.isShowing()) {
                getString(R.string.overlay_preview_close)
            } else {
                getString(R.string.action_start_overlay_execution)
            }
        }
    }

    // ==================== 业务回调 ====================

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

    private fun stringOrFallback(name: String, fallback: String): String {
        val id = resources.getIdentifier(name, "string", packageName)
        return if (id == 0) fallback else getString(id)
    }
}
