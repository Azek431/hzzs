package top.azek431.hzzs.mcp

import org.json.JSONArray
import org.json.JSONObject
import top.azek431.hzzs.BuildConfig

/**
 * MCP JSON-RPC 协议层：握手、通知、方法分发。
 *
 * 兼容 Streamable HTTP 2025-06-18：
 * - initialize 协商版本并创建会话
 * - notifications/initialized 标记就绪（HTTP 202，无 body）
 * - 无 id 的 notification 不当作请求响应
 * - 未 initialize 前除 initialize/ping 外拒绝业务方法
 */
class McpProtocol(
    private val sessions: McpSessionManager,
    private val actions: McpActionSurface,
    private val serverName: String = "HZZS",
    private val serverVersion: String = BuildConfig.VERSION_NAME,
) {
    sealed class DispatchResult {
        data class JsonResponse(val status: Int, val body: JSONObject, val sessionId: String? = null) : DispatchResult()
        data class Accepted(val sessionId: String? = null) : DispatchResult()
        data class HttpError(val status: Int, val body: JSONObject?) : DispatchResult()
    }

    suspend fun dispatch(
        root: JSONObject,
        existingSessionId: String?,
        protocolVersionHeader: String?,
    ): DispatchResult {
        val hasId = root.has("id") && !root.isNull("id")
        val id = if (hasId) root.opt("id") else null
        val method = root.optString("method").takeIf { it.isNotBlank() }
        val isNotification = method != null && !hasId
        val isResponse = !root.has("method") && hasId && (root.has("result") || root.has("error"))

        // 客户端回传的 response：本服务不向客户端发请求，忽略并 202。
        if (isResponse) {
            return DispatchResult.Accepted(existingSessionId)
        }

        if (method == null) {
            return DispatchResult.JsonResponse(
                400,
                errorJson(id, McpErrorCodes.INVALID_REQUEST, "缺少 method"),
            )
        }

        // 通知：无 JSON-RPC 响应体。
        if (isNotification) {
            return handleNotification(method, root.optJSONObject("params") ?: JSONObject(), existingSessionId)
        }

        val params = root.optJSONObject("params") ?: JSONObject()
        return try {
            when (method) {
                "initialize" -> handleInitialize(id, params)
                "ping" -> DispatchResult.JsonResponse(200, resultJson(id, JSONObject()))
                else -> handleOperational(id, method, params, existingSessionId, protocolVersionHeader)
            }
        } catch (error: McpRpcException) {
            DispatchResult.JsonResponse(200, errorJson(id, error.code, error.message ?: "错误", error.data))
        } catch (error: IllegalArgumentException) {
            DispatchResult.JsonResponse(
                200,
                errorJson(id, McpErrorCodes.INVALID_PARAMS, error.message ?: "参数无效"),
            )
        } catch (error: IllegalStateException) {
            DispatchResult.JsonResponse(
                200,
                errorJson(id, McpErrorCodes.SERVER_ERROR, error.message ?: "状态错误"),
            )
        } catch (error: Throwable) {
            DispatchResult.JsonResponse(
                200,
                errorJson(
                    id,
                    McpErrorCodes.INTERNAL_ERROR,
                    error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    private fun handleNotification(
        method: String,
        params: JSONObject,
        sessionId: String?,
    ): DispatchResult {
        when (method) {
            "notifications/initialized", "initialized" -> {
                val sid = sessionId
                if (sid == null) {
                    return DispatchResult.HttpError(
                        400,
                        errorJson(null, McpErrorCodes.INVALID_REQUEST, "initialized 需要 Mcp-Session-Id"),
                    )
                }
                if (!sessions.markInitialized(sid)) {
                    return DispatchResult.HttpError(
                        404,
                        errorJson(null, McpErrorCodes.INVALID_REQUEST, "会话不存在或已过期"),
                    )
                }
                return DispatchResult.Accepted(sid)
            }
            "notifications/cancelled" -> {
                // 最小实现：记录忽略；单请求连接模型下取消主要靠断连/Job cancel。
                return DispatchResult.Accepted(sessionId)
            }
            else -> {
                // 未知通知：按规范接受，避免客户端卡死。
                return DispatchResult.Accepted(sessionId)
            }
        }
    }

    private fun handleInitialize(id: Any?, params: JSONObject): DispatchResult {
        val requested = params.optString("protocolVersion").takeIf { it.isNotBlank() }
            ?: McpProtocolVersions.LATEST
        val negotiated = if (requested in McpProtocolVersions.SUPPORTED) {
            requested
        } else {
            McpProtocolVersions.LATEST
        }
        val clientInfo = params.optJSONObject("clientInfo")
        val clientName = clientInfo?.optString("name")?.takeIf { it.isNotBlank() }
        val session = try {
            sessions.createSession(negotiated, clientName)
        } catch (error: IllegalArgumentException) {
            return DispatchResult.JsonResponse(
                200,
                errorJson(id, McpErrorCodes.RATE_LIMITED, error.message ?: "会话过多"),
            )
        }
        val result = JSONObject().apply {
            put("protocolVersion", negotiated)
            put(
                "serverInfo",
                JSONObject()
                    .put("name", serverName)
                    .put("version", serverVersion)
                    .put("title", "火崽崽奇妙屋"),
            )
            put(
                "capabilities",
                JSONObject()
                    .put("tools", JSONObject())
                    .put("resources", JSONObject()),
            )
            put(
                "instructions",
                "HZZS 本地 MCP：仅 loopback + Bearer。" +
                    "写操作受手机权限级约束；不能绕过系统录屏/悬浮窗/无障碍对话框。" +
                    "兼容 RikkaHub、OperitAI、Claude Code 等 Streamable HTTP 客户端。",
            )
        }
        return DispatchResult.JsonResponse(200, resultJson(id, result), session.id)
    }

    private suspend fun handleOperational(
        id: Any?,
        method: String,
        params: JSONObject,
        sessionId: String?,
        protocolVersionHeader: String?,
    ): DispatchResult {
        // 无会话时：允许 ping / tools/list / resources/list（兼容未回传 Session 头的简化客户端）；
        // tools/call 与 resources/read 仍要求有效会话，避免 TRUSTED_SESSION 被无状态滥用。
        val session = sessions.get(sessionId)
        val allowWithoutSession = method in setOf("ping", "tools/list", "resources/list")
        if (session == null && !allowWithoutSession) {
            return DispatchResult.HttpError(
                400,
                errorJson(
                    id,
                    McpErrorCodes.NOT_INITIALIZED,
                    "缺少或无效的 Mcp-Session-Id，请先 initialize",
                ),
            )
        }
        if (session != null &&
            !session.initialized &&
            method in setOf("tools/call", "resources/read")
        ) {
            throw McpRpcException(
                McpErrorCodes.NOT_INITIALIZED,
                "会话未完成 initialized 握手",
            )
        }
        if (!protocolVersionHeader.isNullOrBlank() &&
            protocolVersionHeader !in McpProtocolVersions.SUPPORTED
        ) {
            return DispatchResult.HttpError(
                400,
                errorJson(
                    id,
                    McpErrorCodes.INVALID_PARAMS,
                    "不支持的 MCP-Protocol-Version",
                    JSONObject()
                        .put("supported", JSONArray(McpProtocolVersions.SUPPORTED))
                        .put("requested", protocolVersionHeader),
                ),
            )
        }

        val result = when (method) {
            "tools/list" -> JSONObject().put("tools", McpToolCatalog.toolsJson())
            "tools/call" -> {
                val name = params.requireString("name")
                val arguments = params.optJSONObject("arguments") ?: JSONObject()
                val payload = actions.call(name, arguments, session)
                JSONObject().put(
                    "content",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "text")
                            .put("text", payload.toString()),
                    ),
                ).put("isError", false)
            }
            "resources/list" -> JSONObject().put("resources", McpToolCatalog.resourcesJson())
            "resources/read" -> {
                val uri = params.requireString("uri")
                val value = actions.readResource(uri)
                JSONObject().put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("uri", uri)
                            .put("mimeType", "application/json")
                            .put("text", value.toString()),
                    ),
                )
            }
            else -> throw McpRpcException(
                McpErrorCodes.METHOD_NOT_FOUND,
                "不支持的方法：$method",
            )
        }
        return DispatchResult.JsonResponse(200, resultJson(id, result), session?.id)
    }
}

class McpRpcException(
    val code: Int,
    message: String,
    val data: JSONObject? = null,
) : Exception(message)

internal fun JSONObject.requireString(name: String): String =
    optString(name).takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("缺少参数：$name")
