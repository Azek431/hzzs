// 火崽崽助手（HZZS）视觉识别设置 — Switch/SeekBar 参数渲染器。
//
// 职责：
// - 渲染 Switch 开关控件
// - 渲染 SeekBarInt（整数滑块）控件
// - 渲染 SeekBarFloat（浮点数滑块）控件
//
// 设计原因：
// - 这三种控件共享类似的行布局结构（createBaseRow）
// - 都涉及数值更新和事件绑定，逻辑相近

package top.azek431.hzzs.ui.settings

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.content.ContextCompat
import top.azek431.hzzs.R

/**
 * Switch/SeekBar 参数渲染器。
 *
 * 负责渲染开关和滑块类控件，处理事件监听和数据绑定。
 */
class SwitchSeekBarParamRenderer(
    private val context: Context,
    private val currentValues: MutableMap<String, Any>,
) {

    /** 创建 Switch 行 */
    fun createSwitchRow(def: ParamDef.Switch, baseRow: LinearLayout): View {
        val topRow = LinearLayout(baseRow.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val infoLayout = LinearLayout(topRow.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = context.dpToPx(12) }
        }

        val label = TextView(context).apply {
            text = def.summary
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val switch = Switch(context).apply {
            isChecked = (currentValues[def.key] as Boolean?) == true
            setOnCheckedChangeListener { _, checked ->
                currentValues[def.key] = checked
            }
        }

        infoLayout.addView(label)
        topRow.addView(infoLayout)
        topRow.addView(switch)
        baseRow.addView(topRow)
        return baseRow
    }

    /** 创建 SeekBarInt 行 */
    fun createSeekBarIntRow(def: ParamDef.SeekBarInt, baseRow: LinearLayout): View {
        val topRow = LinearLayout(baseRow.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val label = TextView(context).apply {
            text = def.label
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = context.dpToPx(8) }
        }

        val valueText = TextView(context).apply {
            val defaultVal = def.defaultValue
            currentValues[def.key] = defaultVal
            text = "$defaultVal${def.unit}"
            setTextColor(ContextCompat.getColor(context, R.color.brand_primary))
            textSize = 14f
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        topRow.addView(label)
        topRow.addView(valueText)
        baseRow.addView(topRow)

        val seekBar = AppCompatSeekBar(context).apply {
            max = ((def.max - def.min) / def.step).toInt()
            progress = ((def.defaultValue - def.min) / def.step).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = def.min + progress * def.step
                    currentValues[def.key] = value
                    valueText.text = "$value${def.unit}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpToPx(6) }
        }

        val summary = TextView(context).apply {
            text = def.summary
            setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpToPx(4) }
        }

        baseRow.addView(seekBar)
        baseRow.addView(summary)
        return baseRow
    }

    /** 创建 SeekBarFloat 行 */
    fun createSeekBarFloatRow(def: ParamDef.SeekBarFloat, baseRow: LinearLayout): View {
        val topRow = LinearLayout(baseRow.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val label = TextView(context).apply {
            text = def.label
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = context.dpToPx(8) }
        }

        val valueText = TextView(context).apply {
            val defaultVal = def.defaultValue
            currentValues[def.key] = defaultVal
            text = formatFloatValue(defaultVal, def)
            setTextColor(ContextCompat.getColor(context, R.color.brand_primary))
            textSize = 14f
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        topRow.addView(label)
        topRow.addView(valueText)
        baseRow.addView(topRow)

        val seekBar = AppCompatSeekBar(context).apply {
            val stepFloat = def.step
            val range = def.max - def.min
            val steps = (range / stepFloat).toInt()
            max = steps
            progress = ((def.defaultValue - def.min) / stepFloat).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = def.min + progress * stepFloat
                    val rounded = kotlin.math.round(value * 100f) / 100f
                    currentValues[def.key] = rounded
                    valueText.text = formatFloatValue(rounded, def)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpToPx(6) }
        }

        val summary = TextView(context).apply {
            text = def.summary
            setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpToPx(4) }
        }

        baseRow.addView(seekBar)
        baseRow.addView(summary)
        return baseRow
    }

    /** 格式化浮点数显示 */
    private fun formatFloatValue(value: Float, def: ParamDef.SeekBarFloat): String {
        return if (def.formatPercent) "${(value * 100).toInt()}%" else String.format("%.2f%s", value, def.unit)
    }
}

/** 扩展属性：获取 Context 的 Resources */
private val Context.resources: android.content.res.Resources
    get() = applicationContext.resources

/** 扩展属性：获取 Context 的 DisplayMetrics */
private val Context.displayMetrics: android.util.DisplayMetrics
    get() = applicationContext.resources.displayMetrics

/** 扩展方法：dp 转 px */
private fun Context.dpToPx(dp: Int): Int {
    return (dp * resources.displayMetrics.density + 0.5f).toInt()
}

private fun Context.dpToPx(dp: Float): Int {
    return (dp * resources.displayMetrics.density + 0.5f).toInt()
}
