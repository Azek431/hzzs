package top.azek431.hzzs.mcp

/**
 * MCP 运行时状态与审批请求模型。
 *
 * 线程：StateFlow 承载，可跨线程读写；不含 Bearer 以外的敏感长期密钥。
 */

/**
 * 客户端导入 JSON 方言。
 *
 * 此处只影响复制给客户端的 `type` 字段文案。
 * - [RIKKAHUB]：`streamable_http`（RikkaHub / 部分第三方）
 * - [CLAUDE_CODE]：`http`（Claude Code 项目 `.mcp.json` 推荐值；`streamable-http` 为其别名）
 */
enum class McpClientImportDialect {
    RIKKAHUB,
    CLAUDE_CODE,
}

/**
 * 连接地址展示场景。
 *
 * 服务绑定由 [top.azek431.hzzs.core.model.McpConfig.bindLocalhostOnly] 决定：
 * true → `127.0.0.1`；false → `0.0.0.0`（局域网可达）。
 * 本枚举只影响复制/展示用主机名。
 *
 * - [LOOPBACK]：同机 App 直接用 `127.0.0.1`
 * - [ADB_FORWARD]：电脑经 `adb forward` 后仍写 `127.0.0.1`
 * - [LAN]：使用探测到的局域网 IPv4（需服务已允许局域网）
 * - [CUSTOM]：用户自填主机（隧道 / 自建代理等）
 */
enum class McpEndpointDisplayMode {
    LOOPBACK,
    ADB_FORWARD,
    LAN,
    CUSTOM,
}

/** 设置页展示、并供复制到客户端配置的 MCP 运行时状态。 */
data class McpServerState(
    val running: Boolean = false,
    val port: Int = 0,
    val token: String = "",
    /** 当前服务是否强制 Bearer；与配置一致，便于 UI 生成可粘贴片段。 */
    val requireAuth: Boolean = false,
    /**
     * true：仅 loopback；false：已绑 `0.0.0.0`（局域网）。
     * 与配置 [top.azek431.hzzs.core.model.McpConfig.bindLocalhostOnly] 一致。
     */
    val bindLocalhostOnly: Boolean = true,
    /** 运行时探测到的局域网 IPv4（展示用，可能为空）。 */
    val lanAddresses: List<String> = emptyList(),
    val lastError: String? = null,
    val activeSessions: Int = 0,
) {
    /**
     * Streamable HTTP 端点 URL。
     *
     * @param host 主机名或 IP；默认 loopback。空白时回退 `127.0.0.1`。
     */
    fun endpointUrl(host: String = "127.0.0.1"): String {
        val safeHost = host.trim().ifBlank { "127.0.0.1" }
        val bracketed =
            if (':' in safeHost && !safeHost.startsWith('[')) "[$safeHost]" else safeHost
        return "http://$bracketed:$port/mcp"
    }

    /**
     * 按展示场景解析主机。
     *
     * [LAN] 使用 [preferredLanHost] 或 [lanAddresses] 首项；无可用 IP 时回退 loopback。
     */
    fun resolveDisplayHost(
        mode: McpEndpointDisplayMode,
        customHost: String = "",
        preferredLanHost: String = "",
    ): String = when (mode) {
        McpEndpointDisplayMode.LOOPBACK,
        McpEndpointDisplayMode.ADB_FORWARD,
        -> "127.0.0.1"
        McpEndpointDisplayMode.LAN -> {
            preferredLanHost.trim().ifBlank {
                lanAddresses.firstOrNull().orEmpty()
            }.ifBlank { "127.0.0.1" }
        }
        McpEndpointDisplayMode.CUSTOM -> customHost.trim().ifBlank { "127.0.0.1" }
    }

    /** 按展示场景生成端点 URL。 */
    fun endpointUrlFor(
        mode: McpEndpointDisplayMode,
        customHost: String = "",
        preferredLanHost: String = "",
    ): String = endpointUrl(resolveDisplayHost(mode, customHost, preferredLanHost))

    /**
     * 电脑侧把本机端口转到设备 loopback 的 adb 命令。
     * 局域网模式下电脑也可直连 [lanAddresses]，不必 forward。
     */
    fun adbForwardCommand(): String = "adb forward tcp:$port tcp:$port"

    /**
     * 生成客户端导入 JSON（根对象含 `mcpServers`）。
     *
     * @param dialect 客户端方言（`type` 字段）
     * @param host 主机；空白回退 loopback
     * @param serverName MCP 服务器键名
     * 免鉴权时不写 headers。
     */
    fun clientImportJson(
        dialect: McpClientImportDialect = McpClientImportDialect.RIKKAHUB,
        host: String = "127.0.0.1",
        serverName: String = "hzzs",
    ): String {
        val type = when (dialect) {
            McpClientImportDialect.RIKKAHUB -> "streamable_http"
            McpClientImportDialect.CLAUDE_CODE -> "http"
        }
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
                  "type": "$type",
                  "url": "${endpointUrl(host)}"$headersBlock
                }
              }
            }
        """.trimIndent()
    }

    /**
     * RikkaHub「导入 JSON」格式（兼容旧调用）。
     * 等价于 [clientImportJson] + [McpClientImportDialect.RIKKAHUB]。
     */
    fun rikkaHubImportJson(
        serverName: String = "hzzs",
        host: String = "127.0.0.1",
    ): String = clientImportJson(
        dialect = McpClientImportDialect.RIKKAHUB,
        host = host,
        serverName = serverName,
    )
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
