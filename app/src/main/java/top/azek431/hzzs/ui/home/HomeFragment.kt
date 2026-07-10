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
// - 通过 HomeActionCallbacks 接口解耦 MainActivity，避免 (requireActivity() as MainActivity) 强转

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
 *
 * 按钮点击事件通过 HomeActionCallbacks 接口委托给宿主 Activity 处理，
 * 不直接引用 MainActivity 类。
 *
 * 配置变更（如屏幕旋转）时，通过 newInstance() + Bundle 保存 callbacks 接口引用，
 * 确保 FragmentManager 重建 Fragment 时不会丢失回调。
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    companion object {
        private const val ARG_CALLBACKS_CLASS = "callbacks_class"

        /**
         * 创建 HomeFragment 实例（工厂方法）。
         *
         * 通过保存回调接口的 Class 名称，让 FragmentManager 在重建时能恢复回调。
         *
         * @param callbacksClass 实现 HomeActionCallbacks 的 Activity 类
         * @return 新的 HomeFragment 实例
         */
        fun newInstance(callbacksClass: Class<out HomeActionCallbacks>): HomeFragment {
            return HomeFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CALLBACKS_CLASS, callbacksClass.name)
                }
            }
        }

        /**
         * 从 Class 名称恢复回调接口实例。
         *
         * 通过反射获取已注册的 Fragment 实例并调用其回调方法。
         *
         * @param activity 宿主 Activity（必须实现 HomeActionCallbacks）
         * @return 回调接口实例，或 null（反射失败时）
         */
        fun resolveCallbacks(activity: android.app.Activity): HomeActionCallbacks? {
            return try {
                val clazz = Class.forName(activity.javaClass.name)
                if (clazz.isAssignableFrom(activity.javaClass)) {
                    activity as? HomeActionCallbacks
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    /** 当前回调接口实例，在 onViewCreated 中从 Activity 恢复 */
    private lateinit var callbacks: HomeActionCallbacks

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 从 Activity 恢复回调接口
        callbacks = requireActivity() as HomeActionCallbacks

        // 绑定按钮点击事件，委托给 HomeActionCallbacks 接口
        view.findViewById<MaterialButton>(R.id.btnDevelopmentPlan)?.setOnClickListener {
            callbacks.onDevelopmentPlanClicked()
        }

        view.findViewById<MaterialButton>(R.id.btnOverlayExecution)?.setOnClickListener {
            callbacks.onOverlayToggleClicked()
        }

        view.findViewById<MaterialButton>(R.id.btnDisclaimer)?.setOnClickListener {
            callbacks.onDisclaimerClicked()
        }
    }
}
