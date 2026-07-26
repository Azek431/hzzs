package top.azek431.hzzs.mcp.executor

import org.json.JSONObject
import top.azek431.hzzs.core.model.McpPermissionLevel
import top.azek431.hzzs.core.model.McpToolPolicy
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.mcp.McpAccessLog
import top.azek431.hzzs.mcp.McpEventBus
import top.azek431.hzzs.mcp.McpToolCatalog
import top.azek431.hzzs.mcp.McpToolLabels
import top.azek431.hzzs.mcp.McpToolPolicySupport
import top.azek431.hzzs.mcp.McpUiBridge
import javax.inject.Inject
import top.azek431.hzzs.mcp.generateMcpAuthToken
import top.azek431.hzzs.mcp.ok
import top.azek431.hzzs.mcp.requireString

/**
 * MCP 自管执行器：启停 / 权限级 / Bearer 鉴权 / 工具策略 / 访问日志 / 运行态查询。
 *
 * 这些工具直接操控 MCP 自身配置（经 [SettingsRepository]）和 [McpUiBridge] 审批流，
 * 被标记为 HIGH_RISK（防自提权）。
 */
class McpSelfManagementExecutor @Inject constructor(
    private val settings: SettingsRepository,
    private val uiBridge: McpUiBridge,
) : ToolExecutor {
    override val toolNames: Set<String> = setOf(
        "set_mcp_enabled",
        "set_mcp_permission_level",
        "set_mcp_auth",
        "set_mcp_tool_policy",
        "get_mcp_status",
        "list_mcp_tools",
        "get_mcp_access_log",
        "clear_mcp_access_log",
    )

    override suspend fun execute(tool: String, arguments: JSONObject): JSONObject = when (tool) {
        "set_mcp_enabled" -> {
            val enabled = arguments.getBoolean("enabled")
            applyConfig({ it.copy(mcp = it.mcp.copy(enabled = enabled)) }, persist = true)
            ok(
                if (enabled) {
                    "MCP 已请求启用（保存后前台服务将启动）"
                } else {
                    "MCP 已请求关闭（当前连接可能即将断开）"
                },
            ).put("enabled", enabled)
        }
        "set_mcp_permission_level" -> {
            val level = enumValueOf<McpPermissionLevel>(arguments.requireString("permissionLevel"))
            applyConfig({ it.copy(mcp = it.mcp.copy(permissionLevel = level)) }, persist = true)
            ok("MCP 权限级已设为 ${level.name}").put("permissionLevel", level.name)
        }
        "set_mcp_auth" -> executeSetMcpAuth(arguments)
        "set_mcp_tool_policy" -> executeSetMcpToolPolicy(arguments)
        "get_mcp_status" -> mcpStatusJson()
        "list_mcp_tools" -> listMcpToolsJson(arguments.optBoolean("includeDisabled", true))
        "get_mcp_access_log" -> {
            val limit = arguments.optInt("limit", 50).coerceIn(1, McpAccessLog.CAPACITY)
            val newestFirst = arguments.optBoolean("newestFirst", true)
            JSONObject()
                .put("enabled", McpAccessLog.isEnabled())
                .put("count", McpAccessLog.size())
                .put("capacity", McpAccessLog.CAPACITY)
                .put("entries", McpAccessLog.toJsonArray(limit, newestFirst))
        }
        "clear_mcp_access_log" -> {
            McpAccessLog.clear()
            ok("MCP 访问日志已清空")
        }
        else -> throw IllegalArgumentException("未知工具：$tool")
    }

    private suspend fun executeSetMcpAuth(arguments: JSONObject): JSONObject {
        val requireAuth = arguments.getBoolean("requireAuth")
        val rotate = arguments.optBoolean("rotateToken", false)
        val snap = settings.current()
        var token = snap.mcp.authToken
        if (requireAuth && (token.isBlank() || rotate)) {
            token = generateMcpAuthToken()
        }
        applyConfig(
            {
                it.copy(
                    mcp = it.mcp.copy(
                        requireAuth = requireAuth,
                        authToken = if (requireAuth) token else it.mcp.authToken,
                    ),
                )
            },
            persist = true,
        )
        return ok(
            buildString {
                append(if (requireAuth) "已开启 Bearer 鉴权" else "已关闭 Bearer 鉴权")
                if (requireAuth && rotate) append("；令牌已轮换，请重新复制导入 JSON")
            },
        )
            .put("requireAuth", requireAuth)
            .put("tokenConfigured", requireAuth && token.isNotBlank())
            .put("tokenRotated", requireAuth && rotate)
    }

    private suspend fun executeSetMcpToolPolicy(arguments: JSONObject): JSONObject {
        val toolName = arguments.requireString("tool")
        val policy = enumValueOf<McpToolPolicy>(arguments.requireString("policy"))
        check(McpToolCatalog.tool(toolName) != null) {
            "未知工具：$toolName（可用 list_mcp_tools 查看准确名）"
        }
        applyConfig(
            { cfg ->
                val nextPolicies = cfg.mcp.toolPolicies.toMutableMap()
                if (policy == McpToolPolicy.DEFAULT) {
                    nextPolicies.remove(toolName)
                } else {
                    nextPolicies[toolName] = policy
                }
                cfg.copy(mcp = cfg.mcp.copy(toolPolicies = nextPolicies))
            },
            persist = true,
        )
        return ok("工具 $toolName 策略已设为 ${policy.name}")
            .put("tool", toolName)
            .put("policy", policy.name)
            .put("titleZh", McpToolLabels.titleZh(toolName))
    }

    private suspend fun mcpStatusJson(): JSONObject {
        val snap = settings.current()
        val mcp = snap.mcp
        val state = uiBridge.serverState.value
        val port = if (state.running && state.port > 0) state.port else mcp.port
        val bindLocalhostOnly = if (state.running) state.bindLocalhostOnly else mcp.bindLocalhostOnly
        return JSONObject().apply {
            put("serverName", "hzzs")
            put("configEnabled", mcp.enabled)
            put("running", state.running)
            put("port", port)
            put("bind", if (bindLocalhostOnly) "127.0.0.1" else "0.0.0.0")
            put("bindLocalhostOnly", bindLocalhostOnly)
            put("endpointLoopback", "http://127.0.0.1:$port/mcp")
            put("permissionLevel", mcp.permissionLevel.name)
            put("requireAuth", mcp.requireAuth)
            put("tokenConfigured", mcp.authToken.isNotBlank())
            put("allowDebugFrames", mcp.allowDebugFrames)
            put("accessLogEnabled", mcp.accessLogEnabled)
            put("accessLogCount", McpAccessLog.size())
            put("eventCount", McpEventBus.size())
            put("activeSessions", state.activeSessions)
            put("lastError", state.lastError)
            put(
                "toolPolicies",
                JSONObject().apply {
                    mcp.toolPolicies.forEach { (name, policy) -> put(name, policy.name) }
                },
            )
            put("enabledToolCount", McpToolPolicySupport.effectiveTools(mcp).size)
            put("totalToolCount", McpToolCatalog.tools.size)
            put(
                "toolPolicyValues",
                org.json.JSONArray(McpToolPolicy.entries.map { it.name }),
            )
            put(
                "permissionLevels",
                org.json.JSONArray(McpPermissionLevel.entries.map { it.name }),
            )
        }
    }

    private suspend fun listMcpToolsJson(includeDisabled: Boolean): JSONObject {
        val mcp = settings.current().mcp
        val arr = org.json.JSONArray()
        McpToolCatalog.tools.forEach { tool ->
            val policy = mcp.policyFor(tool.name)
            if (!includeDisabled && policy == McpToolPolicy.DISABLED) return@forEach
            arr.put(
                JSONObject()
                    .put("name", tool.name)
                    .put("titleZh", McpToolLabels.titleZh(tool.name))
                    .put("description", tool.description)
                    .put("risk", tool.risk.name)
                    .put("policy", policy.name)
                    .put("enabled", policy != McpToolPolicy.DISABLED)
                    .put("clientDescription", McpToolLabels.clientDescription(tool)),
            )
        }
        return JSONObject().put("tools", arr).put("count", arr.length())
    }

    private suspend fun applyConfig(transform: (top.azek431.hzzs.core.model.AppConfig) -> top.azek431.hzzs.core.model.AppConfig, persist: Boolean) {
        val next = transform(settings.current())
        if (persist) settings.save(next) else settings.preview(next)
    }
}
