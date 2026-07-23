package top.azek431.hzzs.mcp

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.URI
import java.security.MessageDigest

/**
 * 最小 HTTP/1.1 读写与鉴权辅助。
 *
 * 仅服务 loopback MCP 桥；不实现 keep-alive 复用多请求（每连接一请求，Connection: close）。
 */

data class HttpRequest(
    val method: String,
    val path: String,
    val authorization: String?,
    val origin: String?,
    val accept: String?,
    val mcpSessionId: String?,
    val mcpProtocolVersion: String?,
    val body: String,
)

fun readHttpRequest(input: BufferedInputStream): HttpRequest {
    val headerBytes = ArrayList<Byte>(1024)
    var matched = 0
    while (headerBytes.size < McpLimits.MAX_HEADER_BYTES) {
        val value = input.read()
        require(value >= 0) { "连接提前结束" }
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
    return HttpRequest(
        method = requestLine[0],
        path = requestLine[1].substringBefore('?'),
        authorization = authorization,
        origin = origin,
        accept = accept,
        mcpSessionId = mcpSessionId?.takeIf { it.isNotBlank() },
        mcpProtocolVersion = mcpProtocolVersion?.takeIf { it.isNotBlank() },
        body = body.toString(Charsets.UTF_8),
    )
}

/**
 * 浏览器会带 Origin；CLI/ADB 通常省略。
 * 拒绝非 loopback Origin，防止 DNS rebinding 或跨源页面触达本机 MCP 桥。
 */
fun isAllowedLoopbackOrigin(origin: String?): Boolean {
    if (origin.isNullOrBlank()) return true
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
        // 长度不同也走一次 isEqual 风格的固定开销比较（对齐到 token 长度的零填充无意义，直接 false）。
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
) {
    val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
    val phrase = when (status) {
        200 -> "OK"
        202 -> "Accepted"
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
    extraHeaders.forEach { (k, v) ->
        output.write("$k: $v\r\n".toByteArray())
    }
    output.write("Connection: close\r\n\r\n".toByteArray())
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
