// 火崽崽助手（HZZS）视觉识别设置 — 参数控件渲染器。
//
// 职责：
// - 根据 ParamDef 类型创建对应的 UI 控件
// - 委托给 BasicParamRenderer / SwitchSeekBarParamRenderer / ComplexParamRenderer
//
// 设计原因：
// - 将 605 行的单一文件拆成 3 个职责清晰的渲染器
// - 每个渲染器只负责一类控件，便于维护和测试
// - ParamRenderer 作为入口，统一分发

package top.azek431.hzzs.ui.settings

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import top.azek431.hzzs.R

/**
 * 参数控件渲染器（入口类）。
 *
 * 根据 ParamDef 类型分发到对应的子渲染器。
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

    /** 基础控件渲染器 */
    private val basicRenderer = BasicParamRenderer(context)

    /** Switch/SeekBar 控件渲染器 */
    private val switchSeekRenderer = SwitchSeekBarParamRenderer(context, currentValues)

    /** 复杂控件渲染器 */
    private val complexRenderer = ComplexParamRenderer(context, currentValues)

    /**
     * 根据 ParamDef 创建对应的 View。
     *
     * @param def 参数定义
     * @return 创建的 View，Spacer 返回 null
     */
    fun render(def: ParamDef): View? = when (def) {
        is ParamDef.Spacer -> basicRenderer.createSpacer(def.dp)
        is ParamDef.Label -> basicRenderer.createLabel(def.text)
        is ParamDef.Note -> basicRenderer.createNote(def.text)
        is ParamDef.Switch -> {
            val row = basicRenderer.createBaseRow()
            switchSeekRenderer.createSwitchRow(def, row)
            row
        }
        is ParamDef.SeekBarInt -> {
            val row = basicRenderer.createBaseRow()
            switchSeekRenderer.createSeekBarIntRow(def, row)
            row
        }
        is ParamDef.SeekBarFloat -> {
            val row = basicRenderer.createBaseRow()
            switchSeekRenderer.createSeekBarFloatRow(def, row)
            row
        }
        is ParamDef.Spinner -> {
            val row = basicRenderer.createBaseRow()
            complexRenderer.createSpinnerRow(def, row)
            row
        }
        is ParamDef.ColorPicker -> {
            val row = basicRenderer.createBaseRow()
            complexRenderer.createColorPickerRow(def, row)
            row
        }
        is ParamDef.RGBThreshold -> {
            val row = basicRenderer.createBaseRow()
            complexRenderer.createRGBThresholdRow(def, row)
            row
        }
    }
}
