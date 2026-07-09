// 火崽崽助手（HZZS）首页 Fragment。
//
// 职责：
// - 承载首页所有内容（状态卡片、功能规划、社区链接、页脚）
// - 通过底部导航栏与设置 Fragment 切换显示
//
// 设计原因：
// - 将原来 activity_main.xml 中的所有内容移入 Fragment
// - 与 SettingsFragment 共享同一个 Activity 和底部导航栏
// - 切换时保留页面状态，不重新创建

package top.azek431.hzzs.ui.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
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
    }
}
