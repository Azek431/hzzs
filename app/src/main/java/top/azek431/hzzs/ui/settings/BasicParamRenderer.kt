// 火崽崽助手（HZZS）视觉识别设置 — 基础参数控件渲染器。
//
// 职责：
// - 渲染基础控件：Spacer（间距）、Label（标题）、Note（提示文字）
// - 提供公用工具方法：dpToPx、createBaseRow
//
// 设计原因：
// - 将 3 种简单控件从 ParamRenderer 中提取
// - 工具方法集中管理，避免重复代码

package top.azek431.hzzs.ui.settings

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import top.azek431.hzzs.R

/**
 * 基础参数控件渲染器。
 *
 * 负责渲染简单的参数控件（间距、标题、提示文字），
 * 以及提供公用工具方法。
 */
class BasicParamRenderer(
    private val context: Context,
) {

    /** 创建空白间距 */
    fun createSpacer(dp: Int): View? {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(dp)
            )
        }
    }

    /** 创建标题文字 */
    fun createLabel(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(4))
        }
    }

    /** 创建提示文字 */
    fun createNote(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
            textSize = 11f
            setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.status_preparing_container))
                setCornerRadius(dpToPx(8).toFloat())
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
                bottomMargin = dpToPx(4)
            }
        }
    }

    /** 创建基础行容器 */
    fun createBaseRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_settings_row)
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(2)
                bottomMargin = dpToPx(2)
            }
        }
    }

    /** dp 转 px（整数） */
    fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    /** dp 转 px（浮点数） */
    fun dpToPx(dp: Float): Int {
        return (dp * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}
