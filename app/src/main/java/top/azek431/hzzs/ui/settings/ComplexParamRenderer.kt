// 火崽崽助手（HZZS）视觉识别设置 — 复杂参数控件渲染器。
//
// 职责：
// - 渲染 Spinner 下拉选择框
// - 渲染 ColorPicker 颜色选择器
// - 渲染 RGBThreshold RGB 三色阈值
//
// 设计原因：
// - 这三种控件比较复杂，涉及对话框/多控件组合
// - 与简单控件（Switch/SeekBar）分开，便于维护

package top.azek431.hzzs.ui.settings

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.azek431.hzzs.R

/**
 * 复杂参数控件渲染器。
 *
 * 负责渲染较复杂的参数控件（Spinner、ColorPicker、RGBThreshold），
 * 处理事件监听和数据绑定。
 */
class ComplexParamRenderer(
    private val context: Context,
    private val currentValues: MutableMap<String, Any>,
) {

    /** 创建 Spinner 行 */
    fun createSpinnerRow(def: ParamDef.Spinner, baseRow: LinearLayout): View {
        val label = TextView(context).apply {
            text = def.label
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = context.dpToPx(6) }
        }

        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, def.labels)
            setSelection(def.defaultValue)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    currentValues[def.key] = position
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val summary = TextView(context).apply {
            text = def.summary
            setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpToPx(8) }
        }

        baseRow.addView(label)
        baseRow.addView(spinner)
        baseRow.addView(summary)
        return baseRow
    }

    /** 创建颜色选择器行 */
    fun createColorPickerRow(def: ParamDef.ColorPicker, baseRow: LinearLayout): View {
        baseRow.orientation = LinearLayout.HORIZONTAL
        baseRow.gravity = android.view.Gravity.CENTER_VERTICAL

        val colorView = View(context).apply {
            val defaultColor = def.defaultValue
            currentValues[def.key] = defaultColor
            setBackgroundColor(defaultColor)
            layoutParams = LinearLayout.LayoutParams(context.dpToPx(44), context.dpToPx(44)).apply {
                marginEnd = context.dpToPx(14)
                gravity = android.view.Gravity.CENTER
            }
            elevation = context.dpToPx(2f).toFloat()
            foreground = ContextCompat.getDrawable(context, R.drawable.bg_settings_row)?.constantState?.newDrawable()
        }

        val infoLayout = LinearLayout(baseRow.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        val label = TextView(context).apply {
            text = def.label
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }

        val hexText = TextView(context).apply {
            val c = def.defaultValue
            text = String.format("#%06X", 0xFFFFFF and c)
            setTextColor(ContextCompat.getColor(context, R.color.brand_primary))
            textSize = 12f
        }

        val summary = TextView(context).apply {
            text = def.summary
            setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
            textSize = 11f
            setMaxLines(1)
            ellipsize = TextUtils.TruncateAt.END
        }

        infoLayout.addView(label)
        infoLayout.addView(hexText)
        infoLayout.addView(summary)

        colorView.setOnClickListener {
            showColorPickerDialog(def, colorView, hexText)
        }

        baseRow.addView(colorView)
        baseRow.addView(infoLayout)
        return baseRow
    }

    /** 显示颜色选择对话框 */
    private fun showColorPickerDialog(
        def: ParamDef.ColorPicker,
        colorView: View,
        hexText: TextView,
    ) {
        val currentColor = (currentValues[def.key] as Int)
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(currentColor, hsv)

        val builder = MaterialAlertDialogBuilder(context)
        builder.setTitle(R.string.settings_select_color)

        val hsvLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dpToPx(16), context.dpToPx(8), context.dpToPx(16), context.dpToPx(8))
        }

        val colorPreview = View(context).apply {
            setBackgroundColor(currentColor)
            layoutParams = LinearLayout.LayoutParams(context.dpToPx(60), context.dpToPx(60)).apply {
                marginEnd = context.dpToPx(16)
                gravity = android.view.Gravity.CENTER
            }
        }

        val hueBar = AppCompatSeekBar(context).apply {
            max = 360
            progress = (hsv[0] * 360).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpToPx(12) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    hsv[0] = progress.toFloat()
                    colorPreview.setBackgroundColor(android.graphics.Color.HSVToColor(hsv))
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        hsvLayout.addView(colorPreview)
        hsvLayout.addView(hueBar)
        builder.setView(hsvLayout)

        builder.setPositiveButton(R.string.action_confirm) { _, _ ->
            val selectedColor = android.graphics.Color.HSVToColor(hsv)
            currentValues[def.key] = selectedColor
            colorView.setBackgroundColor(selectedColor)
            hexText.text = String.format("#%06X", 0xFFFFFF and selectedColor)
        }
        builder.setNegativeButton(R.string.action_cancel, null)
        builder.show()
    }

    /** 创建 RGB 阈值行 */
    fun createRGBThresholdRow(def: ParamDef.RGBThreshold, baseRow: LinearLayout): View {
        val label = TextView(context).apply {
            text = def.label
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = context.dpToPx(8) }
        }

        val channels = arrayOf(
            Triple("R", def.rKey, def.defaultValueR),
            Triple("G", def.gKey, def.defaultValueG),
            Triple("B", def.bKey, def.defaultValueB),
        )

        for ((channel, key, defaultVal) in channels) {
            val channelRow = LinearLayout(baseRow.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = context.dpToPx(6) }
            }

            val channelLabel = TextView(context).apply {
                text = channel
                setTextColor(getChannelColor(channel))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    context.dpToPx(32), ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = context.dpToPx(8) }
                gravity = android.view.Gravity.CENTER
            }

            val editText = EditText(context).apply {
                hint = "0~255"
                setText(defaultVal.toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                maxWidth = context.dpToPx(60)
                currentValues[key] = defaultVal
                setTextColor(ContextCompat.getColor(context, R.color.on_surface))
                setHintTextColor(ContextCompat.getColor(context, R.color.outline))
                setPadding(context.dpToPx(8), context.dpToPx(4), context.dpToPx(8), context.dpToPx(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.surface_container_high))
                    setCornerRadius(context.dpToPx(4).toFloat())
                    setStroke(1, ContextCompat.getColor(context, R.color.outline_variant))
                }
                doAfterTextChanged { editable ->
                    val value = editable?.toString()?.toIntOrNull()
                    if (value != null && value in 0..255) {
                        currentValues[key] = value
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = context.dpToPx(8) }
            }

            val seekBar = AppCompatSeekBar(context).apply {
                max = 255
                progress = defaultVal
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f
                )
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        currentValues[key] = progress
                        editText.setText(progress.toString())
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }

            channelRow.addView(channelLabel)
            channelRow.addView(editText)
            channelRow.addView(seekBar)
            baseRow.addView(channelRow)
        }

        val summary = TextView(context).apply {
            text = def.summary
            setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpToPx(8) }
        }

        baseRow.addView(label)
        baseRow.addView(summary)
        return baseRow
    }

    /** 获取通道颜色 */
    private fun getChannelColor(channel: String): Int {
        return when (channel) {
            "R" -> android.graphics.Color.parseColor("#FF5252")
            "G" -> android.graphics.Color.parseColor("#4CAF50")
            "B" -> android.graphics.Color.parseColor("#448AFF")
            else -> android.graphics.Color.parseColor("#9E9E9E")
        }
    }
}

/** 扩展方法：dp 转 px */
private fun Context.dpToPx(dp: Int): Int {
    return (dp * resources.displayMetrics.density + 0.5f).toInt()
}

private fun Context.dpToPx(dp: Float): Int {
    return (dp * resources.displayMetrics.density + 0.5f).toInt()
}
