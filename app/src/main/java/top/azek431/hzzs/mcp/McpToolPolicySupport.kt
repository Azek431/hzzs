package top.azek431.hzzs.mcp

import top.azek431.hzzs.core.model.McpConfig
import top.azek431.hzzs.core.model.McpPermissionLevel
import top.azek431.hzzs.core.model.McpToolPolicy

/**
 * MCP 工具级策略解析：与全局 [McpPermissionLevel] 叠加。
 *
 * 不变量：
 * - [McpToolPolicy.DISABLED] 从 list 隐藏且 call 拒绝
 * - 全局 [McpPermissionLevel.READ_ONLY] 仍整表拒绝写
 * - HIGH_RISK 在 TRUSTED_SESSION 下仍拒绝（除非 ALWAYS_ASK 走审批且用户批准）
 * - ALWAYS_ASK 在 FULL_ACCESS / TRUSTED 下仍强制手机确认
 */
object McpToolPolicySupport {
    fun isEnabled(config: McpConfig, toolName: String): Boolean =
        config.policyFor(toolName) != McpToolPolicy.DISABLED

    fun effectiveTools(config: McpConfig): List<McpToolDescriptor> =
        McpToolCatalog.tools.filter { isEnabled(config, it.name) }

    /**
     * 是否需要在手机上弹出审批。
     * READ 工具永远 false（策略 DISABLED 在调用前已拦截）。
     */
    fun requiresPhoneApproval(
        risk: McpToolRisk,
        global: McpPermissionLevel,
        toolPolicy: McpToolPolicy,
    ): Boolean {
        if (risk == McpToolRisk.READ) return false
        return when (toolPolicy) {
            McpToolPolicy.DISABLED -> false // 调用前已拒绝
            McpToolPolicy.ALWAYS_ASK -> true
            McpToolPolicy.DEFAULT,
            McpToolPolicy.ALLOW_WHEN_TRUSTED,
            -> when (global) {
                McpPermissionLevel.READ_ONLY -> false
                McpPermissionLevel.ASK_EVERY_TIME -> true
                McpPermissionLevel.TRUSTED_SESSION -> false // HIGH_RISK 另判
                McpPermissionLevel.FULL_ACCESS -> false
            }
        }
    }

    /**
     * 在不需要审批时是否应直接拒绝（相对「可执行」）。
     * @return null 表示可继续；非空为拒绝原因
     */
    fun hardRejectReason(
        risk: McpToolRisk,
        global: McpPermissionLevel,
        toolPolicy: McpToolPolicy,
        hasTrustedSession: Boolean,
    ): String? {
        if (toolPolicy == McpToolPolicy.DISABLED) {
            return "工具已禁用（McpToolPolicy.DISABLED）"
        }
        if (risk == McpToolRisk.READ) return null
        return when (global) {
            McpPermissionLevel.READ_ONLY -> "MCP 当前为只读模式"
            McpPermissionLevel.ASK_EVERY_TIME -> null // 走审批
            McpPermissionLevel.TRUSTED_SESSION -> {
                if (!hasTrustedSession) {
                    "信任会话无效：请使用会回传 Mcp-Session-Id 的客户端，或改用「每次确认」"
                } else if (risk == McpToolRisk.HIGH_RISK && toolPolicy != McpToolPolicy.ALWAYS_ASK) {
                    // ALWAYS_ASK 会走审批；否则 HIGH_RISK 在 TRUSTED 下拒绝
                    "该操作需要完整访问权限（HIGH_RISK）"
                } else {
                    null
                }
            }
            McpPermissionLevel.FULL_ACCESS -> null
        }
    }
}
