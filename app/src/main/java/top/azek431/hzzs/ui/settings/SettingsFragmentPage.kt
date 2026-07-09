// 火崽崽助手（HZZS）设置 Fragment。
//
// 职责：
// - 承载视觉识别设置的所有参数（5 个分区 tab）
// - 通过 ViewPager2 + TabLayout 分区导航
// - 底部固定"恢复默认值"和"保存"按钮
//
// 设计原因：
// - 将原来 VisionSettingsActivity 的内容移入 Fragment
// - 与 HomeFragment 共享同一个 Activity 和底部导航栏
// - 切换时保留页面状态，不重新创建

package top.azek431.hzzs.ui.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import top.azek431.hzzs.R

/**
 * 设置 Fragment。
 *
 * 包含 ViewPager2 + TabLayout，管理五个设置分区 Fragment：
 * 1. 识别与截图
 * 2. HUD 显示
 * 3. 玩家与绿瓶
 * 4. 检测参数
 * 5. 调试选项
 *
 * 布局文件：fragment_settings_page.xml
 */
class SettingsFragmentPage : Fragment(R.layout.fragment_settings_page) {

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var bottomBar: View
    private lateinit var btnReset: com.google.android.material.button.MaterialButton
    private lateinit var btnSave: android.widget.Button

    private val tabTitles = arrayOf(
        "识别与截图",
        "HUD 显示",
        "玩家与绿瓶",
        "检测参数",
        "调试选项",
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tabLayout = view.findViewById(R.id.tabLayout)
            ?: throw IllegalStateException("tabLayout not found")
        viewPager = view.findViewById(R.id.viewPager)
            ?: throw IllegalStateException("viewPager not found")
        bottomBar = view.findViewById(R.id.bottomBar)
            ?: throw IllegalStateException("bottomBar not found")
        btnReset = view.findViewById(R.id.btnReset)
            ?: throw IllegalStateException("btnReset not found")
        btnSave = view.findViewById(R.id.btnSave)
            ?: throw IllegalStateException("btnSave not found")

        setupViewPager()
        bindActions()
        applyTheme()
    }

    private fun setupViewPager() {
        val pagerAdapter = SettingsPagerAdapter(requireActivity())
        viewPager.adapter = pagerAdapter

        val prefs = requireContext().getSharedPreferences(
            VisionSettingsKeys.PREFS_NAME, android.content.Context.MODE_PRIVATE
        )
        val fragments = listOf(
            SettingsFragment.createInstance(Section.RECOGNITION, prefs),
            SettingsFragment.createInstance(Section.HUD_DISPLAY, prefs),
            SettingsFragment.createInstance(Section.PLAYER_BOTTLE, prefs),
            SettingsFragment.createInstance(Section.DETECTION_PARAMS, prefs),
            SettingsFragment.createInstance(Section.DEBUG_OPTIONS, prefs),
        )
        pagerAdapter.setFragments(fragments)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()
    }

    private fun bindActions() {
        btnReset.setOnClickListener {
            showResetConfirmation()
        }

        btnSave.setOnClickListener {
            syncAllFragments()
            android.widget.Toast.makeText(
                requireContext(),
                getString(R.string.settings_saved),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun syncAllFragments() {
        val pagerAdapter = viewPager.adapter as SettingsPagerAdapter
        for (i in 0 until pagerAdapter.itemCount) {
            val fragment = pagerAdapter.getItem(i)
            fragment?.syncToPrefs()
        }
    }

    private fun applyTheme() {
        bottomBar.setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.surface_container_high)
        )
        btnSave.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.brand_primary))
        btnSave.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_on_primary))
        btnReset.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface))
        btnReset.setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_variant))
        btnReset.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.outline)))
    }

    private fun showResetConfirmation() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_reset_title)
            .setMessage(R.string.settings_reset_message)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                VisionSettingsKeys.resetAll(requireContext())
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_reset_done),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                reloadAllFragments()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun reloadAllFragments() {
        val prefs = requireContext().getSharedPreferences(
            VisionSettingsKeys.PREFS_NAME, android.content.Context.MODE_PRIVATE
        )
        val fragments = listOf(
            SettingsFragment.createInstance(Section.RECOGNITION, prefs),
            SettingsFragment.createInstance(Section.HUD_DISPLAY, prefs),
            SettingsFragment.createInstance(Section.PLAYER_BOTTLE, prefs),
            SettingsFragment.createInstance(Section.DETECTION_PARAMS, prefs),
            SettingsFragment.createInstance(Section.DEBUG_OPTIONS, prefs),
        )
        (viewPager.adapter as SettingsPagerAdapter).setFragments(fragments)
        viewPager.currentItem = 0
        tabLayout.selectTab(tabLayout.getTabAt(0))
    }
}
