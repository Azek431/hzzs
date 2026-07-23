package top.azek431.hzzs.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * 描述驱动的 MCP 工具/资源目录。
 *
 * 严格 JSON Schema（禁止 additionalProperties:true 的空对象），
 * 便于 RikkaHub / OperitAI / Claude 等客户端正确生成 arguments。
 */

enum class McpToolRisk {
    /** 只读，不经写审批。 */
    READ,

    /** 普通写操作，受权限级门控。 */
    WRITE,

    /** 高风险写：TRUSTED_SESSION 仍拒绝，需 FULL_ACCESS。 */
    HIGH_RISK,
}

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val risk: McpToolRisk,
    val inputSchema: JSONObject,
    val required: List<String> = emptyList(),
)

data class McpResourceDescriptor(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String = "application/json",
)

object McpToolCatalog {
    private fun objSchema(
        properties: JSONObject = JSONObject(),
        required: List<String> = emptyList(),
    ): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", properties)
        put("additionalProperties", false)
        if (required.isNotEmpty()) put("required", JSONArray(required))
    }

    private fun emptyObjectSchema(): JSONObject = objSchema()

    val tools: List<McpToolDescriptor> = listOf(
        McpToolDescriptor(
            name = "get_status",
            description = "读取视觉运行时状态（是否运行、后端、FPS、障碍数等）",
            risk = McpToolRisk.READ,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "get_settings",
            description = "读取完整应用设置 JSON",
            risk = McpToolRisk.READ,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "preview_settings",
            description = "临时预览设置；离开设置页可恢复。权限型字段会按安全策略收敛",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject().put(
                    "config",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "完整 AppConfig JSON 字符串"),
                ),
                required = listOf("config"),
            ),
            required = listOf("config"),
        ),
        McpToolDescriptor(
            name = "save_settings",
            description = "永久保存设置。权限型字段会按安全策略收敛，不能静默开启自动操作或自提 MCP 权限",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject().put(
                    "config",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "完整 AppConfig JSON 字符串"),
                ),
                required = listOf("config"),
            ),
            required = listOf("config"),
        ),
        McpToolDescriptor(
            name = "reset_preview",
            description = "清除临时预览并恢复已保存配置",
            risk = McpToolRisk.WRITE,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "start_analysis",
            description = "请求启动屏幕分析（仍受截图权限与运行时门控）",
            risk = McpToolRisk.WRITE,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "stop_analysis",
            description = "停止屏幕分析",
            risk = McpToolRisk.WRITE,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "navigate",
            description = "打开应用内页面：home / runtime / settings / about",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject().put(
                    "route",
                    JSONObject()
                        .put("type", "string")
                        .put("enum", JSONArray(listOf("home", "runtime", "settings", "about"))),
                ),
                required = listOf("route"),
            ),
            required = listOf("route"),
        ),
        McpToolDescriptor(
            name = "set_overlay_visible",
            description = "临时显示或隐藏悬浮窗（预览级，不永久保存）",
            risk = McpToolRisk.WRITE,
            inputSchema = objSchema(
                properties = JSONObject().put(
                    "enabled",
                    JSONObject().put("type", "boolean").put("description", "是否显示悬浮窗"),
                ),
            ),
        ),
        McpToolDescriptor(
            name = "run_diagnostics",
            description = "运行诊断：状态、配置可读性、Native 是否加载、调试帧数量",
            risk = McpToolRisk.READ,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "list_debug_frames",
            description = "列出私有目录中的调试帧元数据（需开发者选项 + MCP 允许）",
            risk = McpToolRisk.READ,
            inputSchema = emptyObjectSchema(),
        ),
        McpToolDescriptor(
            name = "clear_debug_frames",
            description = "清除私有目录中的调试帧",
            risk = McpToolRisk.WRITE,
            inputSchema = emptyObjectSchema(),
        ),
    )

    val resources: List<McpResourceDescriptor> = listOf(
        McpResourceDescriptor("app://status", "status", "当前运行状态"),
        McpResourceDescriptor("app://settings/schema", "settings/schema", "设置 schema 摘要"),
        McpResourceDescriptor("app://settings/current", "settings/current", "当前完整设置"),
        McpResourceDescriptor("app://vision/latest", "vision/latest", "最近一帧视觉结果"),
        McpResourceDescriptor("app://vision/metrics", "vision/metrics", "运行指标"),
        McpResourceDescriptor("app://debug/frames", "debug/frames", "调试帧元数据"),
    )

    private val byName = tools.associateBy { it.name }

    fun tool(name: String): McpToolDescriptor? = byName[name]

    fun toolsJson(): JSONArray = JSONArray().apply {
        tools.forEach { tool ->
            put(
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("inputSchema", tool.inputSchema),
            )
        }
    }

    fun resourcesJson(): JSONArray = JSONArray().apply {
        resources.forEach { resource ->
            put(
                JSONObject()
                    .put("uri", resource.uri)
                    .put("name", resource.name)
                    .put("description", resource.description)
                    .put("mimeType", resource.mimeType),
            )
        }
    }
}
