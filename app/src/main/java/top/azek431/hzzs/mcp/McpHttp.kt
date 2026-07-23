package top.azek431.hzzs.mcp

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.URI
import java.security.MessageDigest

/**
 * 最小 HTTP/1.1 读写与鉴权辅助。
 *
 * 支持同连接 keep-alive 多请求（RikkaHub / OkHttp 默认复用连接）；
 * 仍只服务 loopback MCP 桥，不做通用 HTTP 服务器。
 */

data class HttpRequest(
    val method: String,
    val path: String,
    val authorization: String?,
    val origin: String?,
    val accept: String?,
    val connection: String?,
    val mcpSessionId: String?,
    val mcpProtocolVersion: String?,
    val body: String,
) {
    /** 客户端是否希望关闭连接（默认 keep-alive）。 */
    fun wantsClose(): Boolean =
        connection?.lowercase()?.contains("close") == true
}

/**
 * 读取一个完整 HTTP 请求。连接被对端关闭时返回 null（keep-alive 正常结束）。
 */
fun readHttpRequest(input: BufferedInputStream): HttpRequest? {
    val headerBytes = ArrayList<Byte>(1024)
    var matched = 0
    while (headerBytes.size < McpLimits.MAX_HEADER_BYTES) {
        val value = input.read()
        if (value < 0) {
            // 连接结束：若尚未读到任何字节，视为正常 keep-alive 关闭。
            if (headerBytes.isEmpty()) return null
            throw IllegalArgumentException("连接提前结束")
        }
        headerBytes += value.toByte()
        matched = when {
            matched == 0 && value == '\r'.code -> 1
            matched == 1 && value == '\n'.code -> 2
            matched == 2 && value == '\r'.code -> 3
            matched == 3 && value == '\n'.code -> 4
            else -> 0
        }
        if (matched == 4) break
    }
    require(matched == 4) { "HTTP 头过长" }
    val header = headerBytes.toByteArray().toString(Charsets.US_ASCII)
    val lines = header.split("\r\n")
    val requestLine = lines.first().split(' ')
    require(requestLine.size >= 2)
    var length = 0
    var authorization: String? = null
    var origin: String? = null
    var accept: String? = null
    var connection: String? = null
    var mcpSessionId: String? = null
    var mcpProtocolVersion: String? = null
    lines.drop(1).forEach { line ->
        val index = line.indexOf(':')
        if (index <= 0) return@forEach
        when (line.substring(0, index).trim().lowercase()) {
            "content-length" -> length = line.substring(index + 1).trim().toInt()
            "authorization" -> authorization = line.substring(index + 1).trim()
            "origin" -> origin = line.substring(index + 1).trim()
            "accept" -> accept = line.substring(index + 1).trim()
            "connection" -> connection = line.substring(index + 1).trim()
            "mcp-session-id" -> mcpSessionId = line.substring(index + 1).trim()
            "mcp-protocol-version" -> mcpProtocolVersion = line.substring(index + 1).trim()
        }
    }
    require(length in 0..McpLimits.MAX_BODY_BYTES) { "请求体过大" }
    val body = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val count = input.read(body, offset, length - offset)
        require(count > 0) { "请求体不完整" }
        offset += count
    }
    val rawPath = requestLine[1].substringBefore('?')
    return HttpRequest(
        method = requestLine[0],
        path = normalizeMcpPath(rawPath),
        authorization = authorization,
        origin = origin,
        accept = accept,
        connection = connection,
        mcpSessionId = mcpSessionId?.takeIf { it.isNotBlank() },
        mcpProtocolVersion = mcpProtocolVersion?.takeIf { it.isNotBlank() },
        body = body.toString(Charsets.UTF_8),
    )
}

/** 去掉多余斜杠，保证 `/mcp/` 与 `/mcp` 等价。 */
fun normalizeMcpPath(path: String): String {
    if (path.isBlank()) return "/"
    val collapsed = path.replace(Regex("/{2,}"), "/")
    return if (collapsed.length > 1 && collapsed.endsWith('/')) {
        collapsed.dropLast(1)
    } else {
        collapsed
    }
}

/**
 * 浏览器会带 Origin；CLI/ADB 通常省略。
 * 拒绝非 loopback Origin，防止 DNS rebinding 或跨源页面触达本机 MCP 桥。
 * 字面量 "null"（沙箱/文件源）视为可接受。
 */
fun isAllowedLoopbackOrigin(origin: String?): Boolean {
    if (origin.isNullOrBlank()) return true
    if (origin.equals("null", ignoreCase = true)) return true
    return runCatching {
        val uri = URI(origin)
        val host = uri.host?.lowercase() ?: return false
        uri.scheme in setOf("http", "https") &&
            (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]")
    }.getOrDefault(false)
}

/**
 * 校验 HTTP Authorization 头中的 Bearer 与服务端令牌。
 * 使用恒时比较，避免通过时序泄漏首个不匹配字符。
 */
fun constantTimeBearerMatches(authorization: String?, token: String): Boolean {
    val provided = authorization?.removePrefix("Bearer ")
        ?.takeIf { authorization.startsWith("Bearer ") }
        ?: return false
    val a = provided.toByteArray(Charsets.UTF_8)
    val b = token.toByteArray(Charsets.UTF_8)
    if (a.size != b.size) {
        // 长度不同也走一次 isEqual 风格的固定开销比较。
        MessageDigest.isEqual(b, b)
        return false
    }
    return MessageDigest.isEqual(a, b)
}

fun writeHttp(
    output: BufferedOutputStream,
    status: Int,
    body: JSONObject?,
    extraHeaders: Map<String, String> = emptyMap(),
    keepAlive: Boolean = false,
) {
    val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
    val phrase = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        429 -> "Too Many Requests"
        else -> "Error"
    }
    output.write("HTTP/1.1 $status $phrase\r\n".toByteArray())
    if (bytes != null) {
        output.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray())
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
    } else {
        output.write("Content-Length: 0\r\n".toByteArray())
    }
    // Streamable HTTP 客户端要求 Accept 含 json/sse；响应侧声明可 JSON。
    output.write("Cache-Control: no-store\r\n".toByteArray())
    extraHeaders.forEach { (k, v) ->
        output.write("$k: $v\r\n".toByteArray())
    }
    if (keepAlive) {
        output.write("Connection: keep-alive\r\n\r\n".toByteArray())
    } else {
        output.write("Connection: close\r\n\r\n".toByteArray())
    }
    if (bytes != null) output.write(bytes)
    output.flush()
}

fun errorJson(id: Any?, code: Int, message: String, data: JSONObject? = null): JSONObject =
    JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put(
            "error",
            JSONObject().put("code", code).put("message", message).also { err ->
                if (data != null) err.put("data", data)
            },
        )

fun resultJson(id: Any?, result: JSONObject): JSONObject =
    JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
