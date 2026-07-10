// 火崽崽助手（HZZS）主页 View 引用缓存器。
//
// 职责：
// - 缓存 activity_main.xml 中所有需要用到的 View 引用
// - 如果任何必需 View 不存在，抛出 IllegalStateException 阻止 Activity 继续运行
//
// 不负责：
// - 不处理 Padding 初始值（由 MainInsetCache 处理）
// - 不处理业务逻辑（由 MainActionBinder / MainDialogController 处理）
//
// 设计原因：
// - View 引用缓存与 Activity 解耦，便于单元测试
// - 集中管理所有 View 查找逻辑，避免 findViewById 散落各处
// - 任何 View 缺失都会在 onCreate 阶段立即暴露，而非运行时 NPE

package top.azek431.hzzs.ui.main

import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import top.azek431.hzzs.R

/**
 * 主页 View 引用缓存器。
 *
 * 在 Activity.onCreate 中调用 retrieve()，返回包含所有 View 引用
 * 的 MainViewCache 对象。如果任何必需 View 缺失，抛出异常。
 */
class MainViewCache(private val activity: AppCompatActivity) {

    /** 根容器：CoordinatorLayout，整个页面的最外层布局 */
    val rootContainer: android.view.View
        get() = activity.findViewById(R.id.rootContainer)
            ?: throw IllegalStateException("rootContainer not found in activity_main.xml")

    /** 顶部栏：LinearLayout，包含应用名称和副标题 */
    val topBarContainer: android.view.View
        get() = activity.findViewById(R.id.topBarContainer)
            ?: throw IllegalStateException("topBarContainer not found in activity_main.xml")

    /** 开发计划按钮 */
    val btnDevelopmentPlan: MaterialButton
        get() = activity.findViewById(R.id.btnDevelopmentPlan)
            ?: throw IllegalStateException("btnDevelopmentPlan not found")

    /** 悬浮窗开关按钮 */
    val btnOverlayExecution: MaterialButton
        get() = activity.findViewById(R.id.btnOverlayExecution)
            ?: throw IllegalStateException("btnOverlayExecution not found")

    /** 免责声明与功能设置按钮 */
    val btnDisclaimer: MaterialButton
        get() = activity.findViewById(R.id.btnDisclaimer)
            ?: throw IllegalStateException("btnDisclaimer not found")

    /** 社区 QQ 群链接 TextView */
    val textCommunityQqLink: TextView
        get() = activity.findViewById(R.id.textCommunityQqLink)
            ?: throw IllegalStateException("textCommunityQqLink not found")

    /** 社区 Telegram 链接 TextView */
    val textCommunityTelegramLink: TextView
        get() = activity.findViewById(R.id.textCommunityTelegramLink)
            ?: throw IllegalStateException("textCommunityTelegramLink not found")

    /**
     * 检索所有 View 引用。
     *
     * @return 包含所有缓存 View 的对象
     */
    fun retrieve(): MainViewCacheResult {
        return MainViewCacheResult(
            rootContainer = rootContainer,
            topBarContainer = topBarContainer,
            btnDevelopmentPlan = btnDevelopmentPlan,
            btnOverlayExecution = btnOverlayExecution,
            btnDisclaimer = btnDisclaimer,
            textCommunityQqLink = textCommunityQqLink,
            textCommunityTelegramLink = textCommunityTelegramLink,
        )
    }
}

/**
 * View 缓存结果。
 *
 * 封装所有缓存的 View 引用，传递给 MainActivity 使用。
 * 使用 data class 保证不可变性，避免外部修改。
 */
data class MainViewCacheResult(
    val rootContainer: android.view.View,
    val topBarContainer: android.view.View,
    val btnDevelopmentPlan: MaterialButton,
    val btnOverlayExecution: MaterialButton,
    val btnDisclaimer: MaterialButton,
    val textCommunityQqLink: TextView,
    val textCommunityTelegramLink: TextView,
)
