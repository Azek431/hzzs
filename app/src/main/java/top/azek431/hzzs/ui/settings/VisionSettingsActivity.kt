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
import android.util.TypedValue
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
     * 使用自定义 Adapter（非 FragmentStateAdapter）避免编译期依赖问题。
     */
    private fun setupViewPager() {
        val pagerAdapter = SettingsPagerAdapter(this, supportFragmentManager, lifecycle)
        viewPager.adapter = pagerAdapter

        // 创建 5 个分区 Fragment
        val fragments = listOf(
            createInstance(Section.RECOGNITION, prefs),
            createInstance(Section.HUD_DISPLAY, prefs),
            createInstance(Section.PLAYER_BOTTLE, prefs),
            createInstance(Section.DETECTION_PARAMS, prefs),
            createInstance(Section.DEBUG_OPTIONS, prefs),
        )
        pagerAdapter.setFragments(fragments)

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
        for (i in 0 until pagerAdapter.itemCount) {
            val fragment = pagerAdapter.getItem(i)
            fragment?.syncToPrefs()
        }
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
        val fragments = listOf(
            createInstance(Section.RECOGNITION, prefs),
            createInstance(Section.HUD_DISPLAY, prefs),
            createInstance(Section.PLAYER_BOTTLE, prefs),
            createInstance(Section.DETECTION_PARAMS, prefs),
            createInstance(Section.DEBUG_OPTIONS, prefs),
        )
        (viewPager.adapter as SettingsPagerAdapter).setFragments(fragments)
        viewPager.currentItem = 0
        tabLayout.selectTab(tabLayout.getTabAt(0))
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
//  SettingsPagerAdapter — ViewPager2 适配器
// ============================================================================

/**
 * ViewPager2 适配器，管理五个设置分区 Fragment。
 *
 * 由于当前项目环境中 androidx.viewpager2 的 FragmentStateAdapter
 * 无法被 Kotlin 编译器解析，此处手动实现等效的 RecyclerView.Adapter。
 *
 * Fragment 生命周期管理：
 * - setFragments() 会先销毁旧的 Fragment（beginTransaction.remove），
 *   然后替换为新的 Fragment 列表
 * - 使用 commitNowAllowingStateLoss 避免 FragmentState 不一致问题
 */
class SettingsPagerAdapter(
    private val context: android.content.Context,
    fragmentManager: FragmentManager,
    lifecycle: androidx.lifecycle.Lifecycle,
) : RecyclerView.Adapter<SettingsPagerAdapter.FragViewHolder>() {

    private val layoutManager = LinearLayoutManager(context)

    /** Fragment 管理器 */
    private val fragmentManager: FragmentManager = fragmentManager

    /** 当前 Fragment 列表 */
    private var fragments: MutableList<SettingsFragment> = mutableListOf()

    /**
     * 设置新的 Fragment 列表。
     *
     * 先通过 FragmentManager 销毁旧 Fragment，再替换为新列表。
     * 使用 commitNowAllowingStateLoss 避免状态丢失问题。
     *
     * @param list 新的 Fragment 列表
     */
    fun setFragments(list: List<SettingsFragment>) {
        // 先销毁旧的 Fragment
        val transaction = fragmentManager.beginTransaction()
        for (frag in fragments) {
            transaction.remove(frag)
        }
        transaction.commitNowAllowingStateLoss()
        fragments.clear()
        fragments.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): FragViewHolder {
        val container = android.widget.FrameLayout(parent.context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            id = viewType
        }
        return FragViewHolder(container)
    }

    override fun onBindViewHolder(holder: FragViewHolder, position: Int) {
        val frag = fragments[position]
        // 使用 FragmentManager 添加/显示 Fragment
        val transaction = fragmentManager.beginTransaction()
        val existing = fragmentManager.findFragmentByTag("f$position")
        if (existing != null) {
            transaction.show(existing)
        } else {
            transaction.replace(holder.container.id, frag, "f$position")
        }
        transaction.commitNowAllowingStateLoss()
        holder.container.tag = frag
    }

    override fun getItemCount() = fragments.size

    override fun getItemViewType(position: Int) = position + 1000 // 唯一 ID

    /**
     * 获取指定位置的 Fragment。
     *
     * @param position Fragment 位置索引
     * @return 对应位置的 Fragment，越界时返回 null
     */
    fun getItem(position: Int): SettingsFragment? {
        return if (position in fragments.indices) fragments[position] else null
    }

    /** ViewHolder，包装 Fragment 容器 */
    class FragViewHolder(val container: android.widget.FrameLayout) :
        RecyclerView.ViewHolder(container)
}
