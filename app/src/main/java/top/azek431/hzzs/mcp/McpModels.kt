package top.azek431.hzzs.mcp

/**
 * MCP 运行时状态与审批请求模型。
 *
 * 线程：StateFlow 承载，可跨线程读写；不含 Bearer 以外的敏感长期密钥。
 */

/** 开发者设置展示、并供复制到客户端配置的 MCP 运行时状态。 */
data class McpServerState(
    val running: Boolean = false,
    val port: Int = 0,
    val token: String = "",
    /** 当前服务是否强制 Bearer；与配置一致，便于 UI 生成可粘贴片段。 */
    val requireAuth: Boolean = true,
    val lastError: String? = null,
    val activeSessions: Int = 0,
) {
    /** Streamable HTTP 端点（同机客户端如 RikkaHub 直接填此 URL）。 */
    fun endpointUrl(): String = "http://127.0.0.1:$port/mcp"

    /**
     * RikkaHub「导入 JSON」格式（根对象含 mcpServers）。
     * 免鉴权时不写 headers，用户无需手填请求头。
     */
    fun rikkaHubImportJson(serverName: String = "hzzs"): String {
        val headersBlock = if (requireAuth && token.isNotBlank()) {
            """
            ,
            "headers": {
              "Authorization": "Bearer $token"
            }
            """.trimIndent()
        } else {
            ""
        }
        return """
            {
              "mcpServers": {
                "$serverName": {
                  "type": "streamable_http",
                  "url": "${endpointUrl()}"$headersBlock
                }
              }
            }
        """.trimIndent()
    }
}

/** 待用户确认的一次 MCP 写操作请求。 */
data class McpApprovalRequest(
    val id: Long,
    val tool: String,
    val summary: String,
)

/** JSON-RPC 错误码（标准 + 应用扩展）。 */
object McpErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val SERVER_ERROR = -32000
    const val UNAUTHORIZED = -32001
    const val FORBIDDEN_ORIGIN = -32002
    const val NOT_INITIALIZED = -32003
    const val RATE_LIMITED = -32004
    const val CANCELLED = -32005
}

/** 服务支持的协议版本（新→旧）。 */
object McpProtocolVersions {
    const val LATEST = "2025-06-18"
    const val FALLBACK_LEGACY = "2025-03-26"
    val SUPPORTED = listOf(LATEST, FALLBACK_LEGACY, "2024-11-05")
}

/** HTTP 层限制与并发。 */
object McpLimits {
    const val MAX_BODY_BYTES = 256 * 1024
    const val MAX_HEADER_BYTES = 16 * 1024
    const val SOCKET_SO_TIMEOUT_MS = 30_000
    const val MAX_CONCURRENT_CONNECTIONS = 8
    const val MAX_SESSIONS = 16
    const val SESSION_IDLE_TTL_MS = 30 * 60_000L
    const val ACCEPT_BACKLOG = 8
    /** 单 TCP 连接 keep-alive 最多处理的请求数，防止长连接占用。 */
    const val MAX_REQUESTS_PER_CONNECTION = 64
}
