package top.azek431.hzzs.core.theme

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.model.AppThemeMode
import top.azek431.hzzs.core.model.OverlayConfig
import top.azek431.hzzs.core.model.OverlayStyle
import top.azek431.hzzs.core.model.ThemeConfig
import top.azek431.hzzs.core.model.ThemePreset

class ThemePackageTest {
    @Test
    fun roundTripPreservesDeclarativeThemeValues() {
        val original = HzzsThemePackage(
            name = "竹影测试",
            author = "tester",
            theme = ThemeConfig(
                mode = AppThemeMode.AMOLED,
                preset = ThemePreset.CUSTOM,
                customSeed = 0xFF2A9D6Fu.toInt(),
                fontScale = 1.2f,
                spacingScale = 1.25f,
            ),
            overlay = OverlayConfig(style = OverlayStyle.COMPACT, backgroundAlpha = 0.55f),
        )

        val decoded = ThemePackageCodec.decode(ThemePackageCodec.encode(original))

        assertEquals(original.name, decoded.name)
        assertEquals(original.theme, decoded.theme)
        assertEquals(original.overlay.style, decoded.overlay.style)
        assertEquals(original.overlay.backgroundAlpha, decoded.overlay.backgroundAlpha)
    }

    @Test
    fun executableAndRemoteFieldsAreIgnored() {
        val raw = JSONObject(ThemePackageCodec.encode(
            HzzsThemePackage(name = "safe", theme = ThemeConfig(), overlay = OverlayConfig()),
        )).apply {
            put("script", "rm -rf /")
            put("fontUrl", "https://example.invalid/font.ttf")
            getJSONObject("theme").put("plugin", "evil")
        }.toString()

        val reencoded = ThemePackageCodec.encode(ThemePackageCodec.decode(raw))

        assertFalse(reencoded.contains("script"))
        assertFalse(reencoded.contains("fontUrl"))
        assertFalse(reencoded.contains("plugin"))
    }

    @Test
    fun oversizedThemeIsRejectedBeforeParsing() {
        val raw = "{" + " ".repeat(HzzsThemePackage.MAX_BYTES) + "}"
        assertThrows(IllegalArgumentException::class.java) {
            ThemePackageCodec.decode(raw)
        }
    }

    @Test
    fun invalidNumbersFallBackOrClamp() {
        val root = JSONObject(ThemePackageCodec.encode(
            HzzsThemePackage(name = "bounds", theme = ThemeConfig(), overlay = OverlayConfig()),
        ))
        root.getJSONObject("theme")
            .put("fontScale", 99.0)
            .put("spacingScale", -10.0)
        root.getJSONObject("overlay")
            .put("backgroundAlpha", 4.0)
            .put("scale", 0.01)

        val decoded = ThemePackageCodec.decode(root.toString())
        assertEquals(1.5f, decoded.theme.fontScale)
        assertEquals(0.75f, decoded.theme.spacingScale)
        assertEquals(1f, decoded.overlay.backgroundAlpha)
        assertEquals(0.6f, decoded.overlay.scale)
        assertTrue(ThemePackageCodec.sha256(root.toString()).matches(Regex("[0-9a-f]{64}")))
    }
}
