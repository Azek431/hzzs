package top.azek431.hzzs.core.theme

import org.json.JSONObject
import top.azek431.hzzs.core.model.*
import java.security.MessageDigest

/**
 * `.hzzstheme` 声明式主题包模型。
 *
 * 安全边界：
 * - 仅 JSON 字段，无脚本 / 字体 / 图标 / 远程 URL
 * - 体积上限 [MAX_BYTES]
 * - 数值在编解码时全部 clamp
 *
 * 包内容只影响外观与悬浮窗展示，不触及截图后端、自动操作或 MCP。
 */
data class HzzsThemePackage(
    val name: String,
    val formatVersion: Int = CURRENT_FORMAT,
    val author: String = "",
    val description: String = "",
    val theme: ThemeConfig,
    val overlay: OverlayConfig,
) {
    companion object {
        const val CURRENT_FORMAT = 1
        /** 主题包未压缩文本最大字节数。 */
        const val MAX_BYTES = 64 * 1024
    }
}

/**
 * 主题包 JSON 编解码。
 *
 * [encode] 截断过长字符串；[decode] 校验 format / 版本 / 体积，
 * 未知枚举回退默认，非法浮点回退安全值。
 */
object ThemePackageCodec {
    /** 导出为 pretty JSON 字符串。 */
    fun encode(value: HzzsThemePackage): String {
        val safeName = value.name.trim().take(64).ifBlank { "未命名主题" }
        return JSONObject().apply {
            put("format", "hzzstheme")
            put("formatVersion", HzzsThemePackage.CURRENT_FORMAT)
            put("name", safeName)
            put("author", value.author.trim().take(64))
            put("description", value.description.trim().take(240))
            put("theme", JSONObject().apply {
                put("mode", value.theme.mode.name)
                put("preset", value.theme.preset.name)
                put("customSeed", value.theme.customSeed)
                put("dynamicColorEnabled", value.theme.dynamicColorEnabled)
                put("fontScale", value.theme.fontScale.toDouble())
                put("cornerScale", value.theme.cornerScale.toDouble())
                put("spacingScale", value.theme.spacingScale.toDouble())
                put("animationScale", value.theme.animationScale.toDouble())
                put("reduceMotion", value.theme.reduceMotion)
                put("highContrast", value.theme.highContrast)
            })
            put("overlay", JSONObject().apply {
                put("style", value.overlay.style.name)
                put("theme", value.overlay.theme.name)
                put("customColor", value.overlay.customColor)
                put("backgroundAlpha", value.overlay.backgroundAlpha.toDouble())
                put("scale", value.overlay.scale.toDouble())
                put("strokeWidthDp", value.overlay.strokeWidthDp.toDouble())
                put("textScale", value.overlay.textScale.toDouble())
                put("orientation", value.overlay.orientation.name)
                put("showBoxes", value.overlay.showBoxes)
                put("showText", value.overlay.showText)
                put("showFps", value.overlay.showFps)
                put("showConfidence", value.overlay.showConfidence)
                put("showDiagnostics", value.overlay.showDiagnostics)
                put("clickThrough", value.overlay.clickThrough)
                put("snapToEdge", value.overlay.snapToEdge)
                put("lockPosition", value.overlay.lockPosition)
            })
        }.toString(2)
    }

    /**
     * 解析主题包。
     *
     * @throws IllegalArgumentException 体积过大、格式不对或版本不支持
     */
    fun decode(raw: String): HzzsThemePackage {
        require(raw.toByteArray(Charsets.UTF_8).size <= HzzsThemePackage.MAX_BYTES) { "主题包过大" }
        val root = JSONObject(raw)
        require(root.optString("format") == "hzzstheme") { "不是 HZZS 主题包" }
        require(root.optInt("formatVersion", -1) == HzzsThemePackage.CURRENT_FORMAT) { "不支持的主题包版本" }
        val theme = root.optJSONObject("theme") ?: error("主题包缺少 theme")
        val overlay = root.optJSONObject("overlay") ?: JSONObject()
        return HzzsThemePackage(
            name = root.optString("name").trim().take(64).ifBlank { "未命名主题" },
            author = root.optString("author").trim().take(64),
            description = root.optString("description").trim().take(240),
            theme = ThemeConfig(
                mode = enumOr(theme.optString("mode"), AppThemeMode.SYSTEM),
                preset = enumOr(theme.optString("preset"), ThemePreset.CUSTOM),
                customSeed = theme.optInt("customSeed", 0xFFFF6B2C.toInt()),
                dynamicColorEnabled = theme.optBoolean("dynamicColorEnabled", true),
                fontScale = theme.optDouble("fontScale", 1.0).toFloat().finite(1f, 0.8f, 1.5f),
                cornerScale = theme.optDouble("cornerScale", 1.0).toFloat().finite(1f, 0f, 2f),
                spacingScale = theme.optDouble("spacingScale", 1.0).toFloat().finite(1f, 0.75f, 1.5f),
                animationScale = theme.optDouble("animationScale", 1.0).toFloat().finite(1f, 0f, 2f),
                reduceMotion = theme.optBoolean("reduceMotion", false),
                highContrast = theme.optBoolean("highContrast", false),
            ),
            overlay = OverlayConfig(
                style = enumOr(overlay.optString("style"), OverlayStyle.DEBUG_HUD),
                theme = enumOr(overlay.optString("theme"), OverlayTheme.FOLLOW_APP),
                customColor = overlay.optInt("customColor", 0xFF20E89B.toInt()),
                backgroundAlpha = overlay.optDouble("backgroundAlpha", 0.7).toFloat().finite(0.7f, 0.1f, 1f),
                scale = overlay.optDouble("scale", 1.0).toFloat().finite(1f, 0.6f, 2f),
                strokeWidthDp = overlay.optDouble("strokeWidthDp", 2.0).toFloat().finite(2f, 0.5f, 8f),
                textScale = overlay.optDouble("textScale", 1.0).toFloat().finite(1f, 0.75f, 2f),
                orientation = enumOr(overlay.optString("orientation"), OverlayOrientation.HORIZONTAL),
                showBoxes = overlay.optBoolean("showBoxes", true),
                showText = overlay.optBoolean("showText", true),
                showFps = overlay.optBoolean("showFps", false),
                showConfidence = overlay.optBoolean("showConfidence", false),
                showDiagnostics = overlay.optBoolean("showDiagnostics", false),
                clickThrough = overlay.optBoolean("clickThrough", true),
                snapToEdge = overlay.optBoolean("snapToEdge", true),
                lockPosition = overlay.optBoolean("lockPosition", false),
            ),
        )
    }

    /** 计算主题原文 SHA-256（十六进制小写），用于导入前后完整性对照。 */
    fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback

    /** finite 且落入 [min, max]，否则 fallback。 */
    private fun Float.finite(fallback: Float, min: Float, max: Float): Float =
        if (isFinite()) coerceIn(min, max) else fallback
}
