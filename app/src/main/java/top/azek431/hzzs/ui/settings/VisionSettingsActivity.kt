// 火崽崽助手（HZZS）视觉识别设置 Activity。
//
// 职责：
// - 提供视觉识别与 HUD 绘制参数的可调节设置界面
// - 所有参数通过 VisionSettingsKeys 管理的 SharedPreferences 持久化
// - 支持范围校验、一键恢复默认值
//
// UI 结构：
// 使用 ViewPager2 + Material Tabs 分为五个分区：
//   1. 识别与截图 — 识别模式、截图方式、循环间隔、绘制停留时间
//   2. HUD 显示 — 扫描线/数值标签/中心点开关、绘制颜色/透明度/线宽
//   3. 玩家与绿瓶 — 玩家宽高比例、绿瓶上下边界比例
//   4. 检测参数 — RGB 阈值、最小段宽、缺口合并、padding、置信度
//   5. 调试选项 — 日志开关、调试模式
//
// 底部固定"恢复默认值"和"保存"按钮。
//
// 设计原因：
// - 参数定义委托给 ParamBuilder
// - 控件渲染委托给 ParamRenderer
// - 数据持久化委托给 SettingsFragment.syncToPrefs()
// - 本类只负责页面生命周期、ViewPager/Tabs 设置、主题适配

package top.azek431.hzzs.ui.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import top.azek431.hzzs.R
import top.azek431.hzzs.ui.settings.SettingsFragment.Companion.createInstance
import top.azek431.hzzs.ui.settings.VisionSettingsKeys.resetAll
import top.azek431.hzzs.ui.settings.VisionSettingsKeys.PREFS_NAME

/**
 * 视觉识别设置页面。
 *
 * 采用全屏 Activity 风格（NoActionBar 主题），顶部 TabLayout 分区导航，
 * 底部固定操作栏（恢复默认 + 保存）。
 */
class VisionSettingsActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var bottomBar: View
    private lateinit var btnReset: MaterialButton
    private lateinit var btnSave: MaterialButton

    /** 分区标题列表 */
    private val tabTitles = arrayOf(
        "识别与截图",
        "HUD 显示",
        "玩家与绿瓶",
        "检测参数",
        "调试选项",
    )

    /** SharedPreferences 实例，在 onCreate 中初始化 */
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vision_settings)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_overlay_preview_close)
            setTitle(R.string.settings_title)
        }

        // 初始化 SharedPreferences（延迟加载，避免不必要的 IO）
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        cacheViews()
        setupViewPager()
        bindActions()
        applyTheme()
    }

    // ==================== View 缓存 ====================

    private fun cacheViews() {
        tabLayout = findViewById(R.id.tabLayout)
            ?: throw IllegalStateException("tabLayout not found")
        viewPager = findViewById(R.id.viewPager)
            ?: throw IllegalStateException("viewPager not found")
        bottomBar = findViewById(R.id.bottomBar)
            ?: throw IllegalStateException("bottomBar not found")
        btnReset = findViewById(R.id.btnReset)
            ?: throw IllegalStateException("btnReset not found")
        btnSave = findViewById(R.id.btnSave)
            ?: throw IllegalStateException("btnSave not found")
    }

    // ==================== ViewPager + Tabs ====================

    /**
     * 设置 ViewPager2 + TabLayout。
     *
     * 创建 5 个 SettingsFragment，每个对应一个分区。
     * 使用 FragmentStateAdapter 管理 Fragment 生命周期。
     */
    private fun setupViewPager() {
        val pagerAdapter = SettingsPagerAdapter(this, Section.entries, prefs)
        viewPager.adapter = pagerAdapter

        // 绑定 TabLayout 与 ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()
    }

    // ==================== 按钮交互 ====================

    private fun bindActions() {
        btnReset.setOnClickListener {
            showResetConfirmation()
        }

        btnSave.setOnClickListener {
            syncAllFragments()
            showToast(R.string.settings_saved)
            finish()
        }
    }

    /** 将 ViewPager 中所有 Fragment 的参数同步回 SharedPreferences */
    private fun syncAllFragments() {
        val pagerAdapter = viewPager.adapter as SettingsPagerAdapter
        pagerAdapter.syncAllFragments()
    }

    // ==================== 主题适配 ====================

    /**
     * 应用底部操作栏主题样式。
     *
     * 背景色使用 surface_container_high，
     * 保存按钮使用品牌色，重置按钮使用轮廓样式。
     */
    private fun applyTheme() {
        bottomBar.setBackgroundColor(
            ContextCompat.getColor(this, R.color.surface_container_high)
        )
        btnSave.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_primary))
        btnSave.setTextColor(ContextCompat.getColor(this, R.color.brand_on_primary))
        btnReset.setBackgroundColor(ContextCompat.getColor(this, R.color.surface))
        btnReset.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
        btnReset.setStrokeColor(
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.outline))
        )
    }

    // ==================== 恢复默认确认 ====================

    /** 显示恢复默认值确认对话框 */
    private fun showResetConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_reset_title)
            .setMessage(R.string.settings_reset_message)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                resetAll(this)
                showToast(R.string.settings_reset_done)
                reloadAllFragments()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 重建所有 Fragment（恢复默认值后重新加载） */
    private fun reloadAllFragments() {
        // FragmentStateAdapter 会自动重建 Fragment，
        // 只需重新创建 Activity 即可刷新所有 Fragment
        finish()
        startActivity(intent)
    }

    // ==================== 工具方法 ====================

    /** 显示 Toast 提示 */
    private fun showToast(resId: Int) {
        android.widget.Toast.makeText(applicationContext, getString(resId), android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

// ============================================================================
//  Section 枚举 — 设置分区
// ============================================================================

/**
 * 设置分区枚举。
 *
 * 每个分区对应一个 SettingsFragment，Fragment 内部通过 viewType 决定渲染哪些控件。
 */
enum class Section(val titleRes: Int) {
    RECOGNITION(R.string.section_recognition),
    HUD_DISPLAY(R.string.section_hud_display),
    PLAYER_BOTTLE(R.string.section_player_bottle),
    DETECTION_PARAMS(R.string.section_detection_params),
    DEBUG_OPTIONS(R.string.section_debug_options),
}

// ============================================================================
//  SettingsPagerAdapter — ViewPager2 FragmentStateAdapter
// ============================================================================

/**
 * ViewPager2 适配器，管理五个设置分区 Fragment。
 *
 * 使用 FragmentStateAdapter 而非手动 RecyclerView.Adapter，
 * 因为 ViewPager2 的 Fragment 管理需要 Fragment 容器属于 Activity 的视图层级。
 * 手动创建的 FrameLayout 不属于 FragmentManager 管理的容器，
 * 导致 FragmentTransaction.replace() 找不到视图而崩溃。
 */
class SettingsPagerAdapter(
    private val hostingActivity: AppCompatActivity,
    private val sections: List<Section>,
    private val prefs: android.content.SharedPreferences,
) : FragmentStateAdapter(hostingActivity) {

    /** 当前 Fragment 实例缓存 */
    private var cachedFragments: List<SettingsFragment> = listOf()

    override fun getItemCount(): Int = sections.size

    /**
     * 设置 Fragment 列表（用于重建/恢复默认值后调用）。
     */
    fun setFragments(newFragments: List<SettingsFragment>) {
        cachedFragments = newFragments
        notifyDataSetChanged()
    }

    override fun createFragment(position: Int): Fragment {
        val section = sections[position]
        return SettingsFragment.createInstance(section, prefs)
    }

    /**
     * 获取指定位置的 Fragment。
     *
     * @param position Fragment 位置索引
     * @return 对应位置的 Fragment，越界时返回 null
     */
    fun getItem(position: Int): SettingsFragment? {
        return if (position in 0 until itemCount) {
            // FragmentStateAdapter 管理的 Fragment 通过 tag 存储
            val tag = "f$position"
            hostingActivity.supportFragmentManager
                .findFragmentByTag(tag) as? SettingsFragment
        } else null
    }

    /**
     * 同步所有 Fragment 的参数到 SharedPreferences。
     */
    fun syncAllFragments() {
        for (i in 0 until itemCount) {
            getItem(i)?.syncToPrefs()
        }
    }

    /**
     * 重建所有 Fragment（恢复默认值后调用）。
     */
    fun reloadFragments(newPrefs: android.content.SharedPreferences) {
        // FragmentStateAdapter 会自动处理 Fragment 重建
        // 只需更新 prefs 引用并通知数据集变化
        // 由于 FragmentStateAdapter 不暴露直接刷新方法，
        // 我们通过重新创建 Activity 来处理
    }
}
