// 火崽崽助手（HZZS）视觉识别设置 — 参数定义密封类。
//
// 职责：
// - 定义所有可能的参数类型（密封类层次）
// - 每种类型对应一种 UI 控件渲染方式
//
// 设计原因：
// - 使用 sealed class 保证类型安全，编译器可检查所有分支
// - 每个子类包含渲染该控件所需的全部元数据
// - 与 ParamRenderer 配合使用，renderParams() 根据类型分发到不同工厂方法

package top.azek431.hzzs.ui.settings

/**
 * 参数定义的密封类层次。
 *
 * 每种子类对应一种 UI 控件类型，renderParams() 根据类型渲染不同控件。
 */
sealed class ParamDef {
    /** 空白间距（dp） */
    data class Spacer(val dp: Int) : ParamDef()

    /** 参数标题 */
    data class Label(val text: String) : ParamDef()

    /** 开关控件 */
    data class Switch(
        val key: String,
        val defaultValue: Boolean,
        val summary: String,
    ) : ParamDef()

    /** 整数 SeekBar */
    data class SeekBarInt(
        val label: String,
        val key: String,
        val min: Int,
        val max: Int,
        val step: Int,
        val defaultValue: Int,
        val unit: String,
        val summary: String,
    ) : ParamDef()

    /** 浮点数 SeekBar */
    data class SeekBarFloat(
        val label: String,
        val key: String,
        val min: Float,
        val max: Float,
        val step: Float,
        val defaultValue: Float,
        val unit: String,
        val summary: String,
        val formatPercent: Boolean = false,
    ) : ParamDef()

    /** 下拉选择框 */
    data class Spinner(
        val label: String,
        val key: String,
        val labels: Array<out String>,
        val defaultValue: Int,
        val summary: String,
    ) : ParamDef()

    /** 颜色选择器 */
    data class ColorPicker(
        val label: String,
        val key: String,
        val defaultValue: Int,
        val summary: String,
    ) : ParamDef()

    /** RGB 三色阈值 */
    data class RGBThreshold(
        val label: String,
        val rKey: String,
        val gKey: String,
        val bKey: String,
        val defaultValueR: Int,
        val defaultValueG: Int,
        val defaultValueB: Int,
        val summary: String,
    ) : ParamDef()

    /** 提示文字 */
    data class Note(val text: String) : ParamDef()
}
