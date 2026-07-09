// 火崽崽助手（HZZS）视觉识别设置 — 参数控件渲染器。
//
// 职责：
// - 根据 ParamDef 类型创建对应的 UI 控件
// - 处理控件的事件监听和数据绑定
//
// 设计原因：
// - 将 8 种控件工厂方法从 SettingsFragment 中提取出来
// - 每个工厂方法自包含，便于理解和测试
// - 减少 SettingsFragment 的行数，提高可读性

package top.azek431.hzzs.ui.settings

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.azek431.hzzs.R

/**
 * 参数控件渲染器。
 *
 * 根据 ParamDef 类型创建对应的 UI 控件，
 * 处理控件的事件监听和数据绑定。
 *
 * @param context 上下文
 * @param currentValues 当前内存值映射（保存用户修改）
 * @param paramContainer 父容器（用于 addView）
 */
class ParamRenderer(
    private val context: Context,
    private val currentValues: MutableMap<String, Any>,
    private val paramContainer: LinearLayout,
) {

    companion object {
        /** SeekBar 进度色 */
        private const val PROGRESS_TINT_RES = android.R.color.holo_blue_dark
    }

    // ==================== 控件工厂方法入口 ====================

    /**
     * 根据 ParamDef 创建对应的 View。
     *
     * @param def 参数定义
     * @return 创建的 View，Spacer 返回 null
     */
    fun render(def: ParamDef): View? = when (def) {
        is ParamDef.Spacer -> createSpacer(def.dp)
        is ParamDef.Label -> createLabel(def.text)
        is ParamDef.Switch -> createSwitchRow(def)
        is ParamDef.SeekBarInt -> createSeekBarIntRow(def)
        is ParamDef.SeekBarFloat -> createSeekBarFloatRow(def)
        is ParamDef.Spinner -> createSpinnerRow(def)
        is ParamDef.ColorPicker -> createColorPickerRow(def)
        is ParamDef.RGBThreshold -> createRGBThresholdRow(def)
        is ParamDef.Note -> createNote(def.text)
    }

    // ==================== 基础控件创建 ====================

    /** 创建空白间距 */
    private fun createSpacer(dp: Int): View? {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(dp)
            )
        }
    }

    /** 创建标题文字 */
    private fun createLabel(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(4))
        }
    }

    /** 创建提示文字 */
    private fun createNote(text: String): TextView {
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

    // ==================== Switch 控件 ====================

    /** 创建 Switch 行 */
    private fun createSwitchRow(def: ParamDef.Switch): View {
        val row = createBaseRow()

        val topRow = LinearLayout(row.context).apply {
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
            ).apply { marginEnd = dpToPx(12) }
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
        row.addView(topRow)
        return row
    }

    // ==================== SeekBarInt 控件 ====================

    /** 创建整数 SeekBar 行 */
    private fun createSeekBarIntRow(def: ParamDef.SeekBarInt): View {
        val row = createBaseRow()

        val topRow = LinearLayout(row.context).apply {
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
            ).apply { marginEnd = dpToPx(8) }
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
        row.addView(topRow)

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
            ).apply { topMargin = dpToPx(6) }
        }

        val summary = TextView(context).apply {
            text = def.summary
            setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(4) }
        }

        row.addView(seekBar)
        row.addView(summary)
        return row
    }

    // ==================== SeekBarFloat 控件 ====================

    /** 创建浮点数 SeekBar 行 */
    private fun createSeekBarFloatRow(def: ParamDef.SeekBarFloat): View {
        val row = createBaseRow()

        val topRow = LinearLayout(row.context).apply {
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
            ).apply { marginEnd = dpToPx(8) }
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
        row.addView(topRow)

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
            ).apply { topMargin = dpToPx(6) }
        }

        val summary = TextView(context).apply {
            text = def.summary
            setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(4) }
        }

        row.addView(seekBar)
        row.addView(summary)
        return row
    }

    private fun formatFloatValue(value: Float, def: ParamDef.SeekBarFloat): String {
        return if (def.formatPercent) "${(value * 100).toInt()}%" else String.format("%.2f%s", value, def.unit)
    }

    // ==================== Spinner 控件 ====================

    /** 创建 Spinner 行 */
    private fun createSpinnerRow(def: ParamDef.Spinner): View {
        val row = createBaseRow()

        val label = TextView(context).apply {
            text = def.label
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(6) }
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
            ).apply { topMargin = dpToPx(8) }
        }

        row.addView(label)
        row.addView(spinner)
        row.addView(summary)
        return row
    }

    // ==================== ColorPicker 控件 ====================

    /** 创建颜色选择器行 */
    private fun createColorPickerRow(def: ParamDef.ColorPicker): View {
        val row = createBaseRow()
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL

        val colorView = View(context).apply {
            val defaultColor = def.defaultValue
            currentValues[def.key] = defaultColor
            setBackgroundColor(defaultColor)
            layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44)).apply {
                marginEnd = dpToPx(14)
                gravity = android.view.Gravity.CENTER
            }
            elevation = dpToPx(2f).toFloat()
            foreground = ContextCompat.getDrawable(context, R.drawable.bg_settings_row)?.constantState?.newDrawable()
        }

        val infoLayout = LinearLayout(row.context).apply {
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

        row.addView(colorView)
        row.addView(infoLayout)
        return row
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
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
        }

        val colorPreview = View(context).apply {
            setBackgroundColor(currentColor)
            layoutParams = LinearLayout.LayoutParams(dpToPx(60), dpToPx(60)).apply {
                marginEnd = dpToPx(16)
                gravity = android.view.Gravity.CENTER
            }
        }

        val hueBar = AppCompatSeekBar(context).apply {
            max = 360
            progress = (hsv[0] * 360).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(12) }
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

    // ==================== RGB Threshold 控件 ====================

    /** 创建 RGB 阈值行 */
    private fun createRGBThresholdRow(def: ParamDef.RGBThreshold): View {
        val row = createBaseRow()

        val label = TextView(context).apply {
            text = def.label
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
        }

        val channels = arrayOf(
            Triple("R", def.rKey, def.defaultValueR),
            Triple("G", def.gKey, def.defaultValueG),
            Triple("B", def.bKey, def.defaultValueB),
        )

        for ((channel, key, defaultVal) in channels) {
            val channelRow = LinearLayout(row.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpToPx(6) }
            }

            val channelLabel = TextView(context).apply {
                text = channel
                setTextColor(getChannelColor(channel))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(32), ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dpToPx(8) }
                gravity = android.view.Gravity.CENTER
            }

            val editText = EditText(context).apply {
                hint = "0~255"
                setText(defaultVal.toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                maxWidth = dpToPx(60)
                currentValues[key] = defaultVal
                setTextColor(ContextCompat.getColor(context, R.color.on_surface))
                setHintTextColor(ContextCompat.getColor(context, R.color.outline))
                setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.surface_container_high))
                    setCornerRadius(dpToPx(4).toFloat())
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
                ).apply { marginEnd = dpToPx(8) }
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
            row.addView(channelRow)
        }

        val summary = TextView(context).apply {
            text = def.summary
            setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(8) }
        }

        row.addView(label)
        row.addView(summary)
        return row
    }

    // ==================== 工具方法 ====================

    /** 创建基础行容器 */
    private fun createBaseRow(): LinearLayout {
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

    /** dp 转 px */
    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * context.resources.displayMetrics.density + 0.5f).toInt()
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
