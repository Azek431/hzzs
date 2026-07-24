package top.azek431.hzzs.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AppThemeMode
import top.azek431.hzzs.core.model.ObstacleKind
import top.azek431.hzzs.core.model.OverlayStyle
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.model.ThemePreset

class McpSettingsPatchTest {
    @Test
    fun appliesThemeAndScenePatches() {
        val base = AppConfig()
        val next = McpSettingsPatch.apply(
            base,
            mapOf(
                "selectedScene" to "BAMBOO_BOOKSTORE",
                "theme.mode" to "DARK",
                "theme.preset" to "OCEAN",
                "overlay.style" to "COMPACT",
                "overlay.showFps" to true,
            ),
        )
        assertEquals(SceneId.BAMBOO_BOOKSTORE, next.selectedScene)
        assertEquals(AppThemeMode.DARK, next.theme.mode)
        assertEquals(ThemePreset.OCEAN, next.theme.preset)
        assertEquals(OverlayStyle.COMPACT, next.overlay.style)
        assertTrue(next.overlay.showFps)
    }

    @Test
    fun appliesSceneThresholdAndObstacles() {
        val base = AppConfig()
        val next = McpSettingsPatch.apply(
            base,
            mapOf(
                "scenes.SEA_SALT_LIVING_ROOM.thresholds.minimumConfidence" to 0.55,
                "scenes.SEA_SALT_LIVING_ROOM.disabledObstacles" to listOf("SEA_PIT", "SAND_CASTLE"),
            ),
        )
        val scene = next.scenes.getValue(SceneId.SEA_SALT_LIVING_ROOM)
        assertEquals(0.55f, scene.thresholds.minimumConfidence, 1e-4f)
        assertTrue(scene.disabledObstacles.contains(ObstacleKind.SEA_PIT))
        assertTrue(scene.disabledObstacles.contains(ObstacleKind.SAND_CASTLE))
    }

    @Test
    fun rejectsUnknownPath() {
        try {
            McpSettingsPatch.apply(AppConfig(), mapOf("automation.enabled" to true))
            assertTrue("should throw", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不支持"))
        }
    }

    @Test
    fun applyFromJsonObject() {
        val patches = JSONObject()
            .put("theme.reduceMotion", true)
            .put("mcp.port", 9001)
        val next = McpSettingsPatch.applyFromJson(AppConfig(), patches)
        assertTrue(next.theme.reduceMotion)
        assertEquals(9001, next.mcp.port)
        assertFalse(next.mcp.requireAuth)
    }

    @Test
    fun catalogHasNewToolsAndStrictSchemas() {
        val names = McpToolCatalog.tools.map { it.name }.toSet()
        listOf(
            "get_runtime_snapshot",
            "patch_settings",
            "set_scene",
            "set_theme",
            "set_developer_enabled",
            "get_automation_gates",
            "list_algorithms",
            "get_logs",
            "export_diagnostics",
            "download_algorithm",
            "get_mcp_status",
            "list_mcp_tools",
            "set_mcp_enabled",
            "set_mcp_permission_level",
            "set_mcp_auth",
            "set_mcp_tool_policy",
        ).forEach { assertTrue("$it missing", names.contains(it)) }
        McpToolCatalog.tools.forEach { tool ->
            assertEquals("object", tool.inputSchema.getString("type"))
            assertFalse(
                "tool ${tool.name} must not open additionalProperties at root",
                tool.inputSchema.optBoolean("additionalProperties", true),
            )
        }
        assertEquals(McpToolRisk.HIGH_RISK, McpToolCatalog.tool("set_developer_enabled")!!.risk)
        assertEquals(McpToolRisk.HIGH_RISK, McpToolCatalog.tool("set_automation_enabled")!!.risk)
        assertEquals(McpToolRisk.HIGH_RISK, McpToolCatalog.tool("download_algorithm")!!.risk)
        assertEquals(McpToolRisk.HIGH_RISK, McpToolCatalog.tool("set_mcp_tool_policy")!!.risk)
        assertEquals(McpToolRisk.HIGH_RISK, McpToolCatalog.tool("set_mcp_permission_level")!!.risk)
        assertTrue(McpToolCatalog.resources.any { it.uri == "app://runtime/snapshot" })
        assertTrue(McpToolCatalog.resources.any { it.uri == "app://algorithm/active" })
        assertTrue(McpToolCatalog.resources.any { it.uri == "app://mcp/status" })
        assertTrue(
            McpToolLabels.clientDescription(McpToolCatalog.tool("get_status")!!)
                .contains("工具名: get_status"),
        )
    }
}
