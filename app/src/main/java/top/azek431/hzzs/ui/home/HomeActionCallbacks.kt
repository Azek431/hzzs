// 火崽崽助手（HZZS）首页 Fragment 操作回调接口。
//
// 职责：
// - 定义 HomeFragment 需要调用的操作接口
// - 让 Activity 实现该接口，HomeFragment 只依赖接口而非具体 Activity 类
//
// 设计原因：
// - 解耦 HomeFragment 和 MainActivity，避免 (requireActivity() as MainActivity) 强转
// - 拆分 Gradle 模块后，HomeFragment 在独立模块中无法直接引用 MainActivity
// - 符合依赖倒置原则：高层模块（MainActivity）依赖低层模块（HomeFragment）定义的接口

package top.azek431.hzzs.ui.home

/**
 * 首页 Fragment 操作回调接口。
 *
 * HomeFragment 通过此接口调用 Activity 的业务方法，
 * 不直接引用 MainActivity 类。
 *
 * 此接口与 MainActivity 实现的 MainActionCallbacks 接口职责一致，
 * 但 HomeFragment 只依赖这个轻量接口，不依赖完整的 Activity。
 */
interface HomeActionCallbacks {

    /** 点击了"查看开发计划"按钮 */
    fun onDevelopmentPlanClicked()

    /** 点击了"悬浮窗开关"按钮 */
    fun onOverlayToggleClicked()

    /** 点击了"免责声明"按钮 */
    fun onDisclaimerClicked()
}
