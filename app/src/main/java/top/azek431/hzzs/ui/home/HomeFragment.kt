// 火崽崽助手（HZZS）首页 Fragment。
//
// 职责：
// - 承载首页所有内容（状态卡片、功能规划、社区链接、页脚）
// - 通过底部导航栏与设置 Fragment 切换显示
// - 绑定按钮点击事件（开发计划、悬浮窗开关、免责声明）
//
// 设计原因：
// - 将原来 activity_main.xml 中的所有内容移入 Fragment
// - 与 SettingsFragment 共享同一个 Activity 和底部导航栏
// - 切换时保留页面状态，不重新创建

package top.azek431.hzzs.ui.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import top.azek431.hzzs.R

/**
 * 首页 Fragment。
 *
 * 包含：状态概览卡片、权限状态卡片、功能规划卡片、社区链接、页脚信息。
 * 布局文件：fragment_home.xml
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 绑定按钮点击事件
        view.findViewById<MaterialButton>(R.id.btnDevelopmentPlan)?.setOnClickListener {
            (requireActivity() as top.azek431.hzzs.MainActivity).onDevelopmentPlanClicked()
        }

        view.findViewById<MaterialButton>(R.id.btnOverlayExecution)?.setOnClickListener {
            (requireActivity() as top.azek431.hzzs.MainActivity).onOverlayToggleClicked()
        }

        view.findViewById<MaterialButton>(R.id.btnDisclaimer)?.setOnClickListener {
            (requireActivity() as top.azek431.hzzs.MainActivity).onDisclaimerClicked()
        }
    }
}
