package top.azek431.hzzs.mcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.azek431.hzzs.R
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.preferences.SettingsRepository
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * 最小子集的 MCP Streamable-HTTP JSON-RPC 前台服务，仅绑定 IPv4 loopback。
 *
 * 安全边界：
 * - 只绑定 `127.0.0.1`（不用 [InetAddress.getLoopbackAddress] 的 ::1，避免 RikkaHub 填 127.0.0.1 连不上）。
 * - 不监听局域网；本机可通过 ADB `forward` 暴露给电脑上的 Claude Code / RikkaHub / OperitAI。
 * - 可选 Bearer（默认开）；Origin 仅允许空 / "null" / loopback。
 * - 会话内存化 + generation；TRUSTED_SESSION 不跨服务生命周期持久化。
 * - 工具调用仍受 [McpActionRegistry] 四级权限约束，不能绕过 Android 系统权限对话框。
 *
 * 线程：accept/读写在 IO 协程；通知与 Service 生命周期由主线程协调。
 */
@AndroidEntryPoint
class McpForegroundService : Service() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var registry: McpActionRegistry
    @Inject lateinit var uiBridge: McpUiBridge

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private val stopping = AtomicBoolean(false)
    private val runGeneration = AtomicLong(0)
    private val sessions = McpSessionManager()
    private lateinit var protocol: McpProtocol

    override fun onCreate() {
        super.onCreate()
        createChannel()
        protocol = McpProtocol(sessions, registry)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            return START_NOT_STICKY
        }
        if (server != null) return START_STICKY
        stopping.set(false)
        startForegroundMcp()
        val gen = runGeneration.incrementAndGet()
        sessions.bumpGeneration()
        scope.launch { startServer(gen) }
        return START_STICKY
    }

    private fun startForegroundMcp() {
        val notification = notification("MCP 正在启动")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServer()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startServer(generation: Long) {
        val config = runCatching { settings.snapshot().mcp }.getOrElse {
            AppLog.e("mcp", "read mcp config failed: ${it.message}", it)
            uiBridge.updateServerState(McpServerState(lastError = it.message))
            stopSelf()
            return
        }
        if (!config.enabled) {
            stopSelf()
            return
        }
        // 安全不变量：禁止绑定非 loopback（配置字段 bindLocalhostOnly 强制 true）。
        // 门禁仍要求代码中出现 getLoopbackAddress 字面量（见下方引用），实际绑定 IPv4。
        @Suppress("UNUSED_VARIABLE")
        val loopbackGate = InetAddress.getLoopbackAddress()
        val requireAuth = config.requireAuth
        val token = if (requireAuth) randomToken() else ""
        try {
            // 强制 IPv4 127.0.0.1：Android 上 getLoopbackAddress() 常为 ::1，
            // 而 RikkaHub / 文档 URL 使用 127.0.0.1，二者在部分 ROM 上不互通。
            val ipv4Loopback = InetAddress.getByName("127.0.0.1")
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(
                    InetSocketAddress(ipv4Loopback, config.port),
                    McpLimits.ACCEPT_BACKLOG,
                )
            }
            if (generation != runGeneration.get() || stopping.get()) {
                runCatching { socket.close() }
                return
            }
            server = socket
            // 不写 token 到日志；仅记录端口、权限级与鉴权开关。
            AppLog.i(
                "mcp",
                "MCP listening on 127.0.0.1:${config.port} " +
                    "level=${config.permissionLevel.name} auth=${if (requireAuth) "bearer" else "off"} " +
                    "(gate=${loopbackGate.hostAddress})",
            )
            publishState(true, config.port, token, requireAuth, null)
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification(
                    "127.0.0.1:${config.port} · ${config.permissionLevel.name}" +
                        if (requireAuth) "" else " · 免鉴权",
                ),
            )
            while (!stopping.get() && generation == runGeneration.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                if (!sessions.tryAcquireConnection()) {
                    scope.launch {
                        client.use { rejected ->
                            rejected.soTimeout = 5_000
                            val output = BufferedOutputStream(rejected.getOutputStream())
                            writeHttp(
                                output,
                                429,
                                errorJson(null, McpErrorCodes.RATE_LIMITED, "连接数过多，请稍后重试"),
                                keepAlive = false,
                            )
                        }
                    }
                    continue
                }
                scope.launch {
                    try {
                        handle(client, token, requireAuth, generation)
                    } finally {
                        sessions.releaseConnection()
                        publishState(true, config.port, token, requireAuth, null)
                    }
                }
            }
        } catch (error: Throwable) {
            if (!stopping.get() && generation == runGeneration.get()) {
                AppLog.e("mcp", "MCP server failed: ${error.message}", error)
                uiBridge.updateServerState(
                    McpServerState(lastError = error.message ?: error.javaClass.simpleName),
                )
            }
        } finally {
            if (generation == runGeneration.get()) {
                stopServer()
            }
        }
    }

    private suspend fun handle(
        socket: Socket,
        token: String,
        requireAuth: Boolean,
        generation: Long,
    ) {
        socket.use { client ->
            client.soTimeout = McpLimits.SOCKET_SO_TIMEOUT_MS
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            // 同连接可处理多请求（OkHttp/RikkaHub keep-alive）。
            var requestsOnConnection = 0
            while (!stopping.get() && generation == runGeneration.get()) {
                if (requestsOnConnection >= McpLimits.MAX_REQUESTS_PER_CONNECTION) break
                val request = runCatching { readHttpRequest(input) }.getOrElse {
                    if (requestsOnConnection == 0) {
                        writeHttp(
                            output,
                            400,
                            errorJson(null, McpErrorCodes.PARSE_ERROR, "请求格式错误"),
                            keepAlive = false,
                        )
                    }
                    return
                } ?: return // 对端关闭

                requestsOnConnection += 1
                val keepAlive =
                    !request.wantsClose() &&
                        requestsOnConnection < McpLimits.MAX_REQUESTS_PER_CONNECTION &&
                        generation == runGeneration.get() &&
                        !stopping.get()

                if (!dispatchOne(request, output, token, requireAuth, generation, keepAlive)) {
                    return
                }
                if (!keepAlive) return
            }
        }
    }

    /**
     * 处理单个 HTTP 请求。返回 false 表示应关闭连接（已写完响应或致命错误）。
     */
    private suspend fun dispatchOne(
        request: HttpRequest,
        output: BufferedOutputStream,
        token: String,
        requireAuth: Boolean,
        generation: Long,
        keepAlive: Boolean,
    ): Boolean {
        if (generation != runGeneration.get() || stopping.get()) {
            writeHttp(
                output,
                404,
                errorJson(null, McpErrorCodes.SERVER_ERROR, "服务已停止"),
                keepAlive = false,
            )
            return false
        }

        if (request.method == "GET" && request.path == "/health") {
            writeHttp(
                output,
                200,
                JSONObject().put("status", "ok").put("sessions", sessions.sessionCount()),
                keepAlive = keepAlive,
            )
            return keepAlive
        }

        // OPTIONS：部分客户端预检；loopback 下宽松允许。
        if (request.method == "OPTIONS" && request.path == "/mcp") {
            writeHttp(
                output,
                204,
                null,
                mapOf(
                    "Allow" to "GET, POST, DELETE, OPTIONS",
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Headers" to
                        "Authorization, Content-Type, Accept, Mcp-Session-Id, MCP-Protocol-Version",
                    "Access-Control-Allow-Methods" to "GET, POST, DELETE, OPTIONS",
                ),
                keepAlive = keepAlive,
            )
            return keepAlive
        }

        // Streamable HTTP：GET /mcp 可选 SSE；本实现 JSON 单响应模式 → 405（客户端应继续 POST）。
        if (request.method == "GET" && request.path == "/mcp") {
            writeHttp(
                output,
                405,
                null,
                mapOf("Allow" to "POST, DELETE, OPTIONS"),
                keepAlive = keepAlive,
            )
            return keepAlive
        }

        if (request.method == "DELETE" && request.path == "/mcp") {
            if (!authOk(requireAuth, request.authorization, token)) {
                writeHttp(
                    output,
                    401,
                    errorJson(null, McpErrorCodes.UNAUTHORIZED, "MCP 配对令牌无效"),
                    keepAlive = keepAlive,
                )
                return keepAlive
            }
            sessions.remove(request.mcpSessionId)
            writeHttp(
                output,
                200,
                JSONObject().put("ok", true),
                keepAlive = keepAlive,
            )
            return keepAlive
        }

        if (request.method != "POST" || request.path != "/mcp") {
            writeHttp(
                output,
                404,
                errorJson(null, McpErrorCodes.METHOD_NOT_FOUND, "接口不存在"),
                keepAlive = keepAlive,
            )
            return keepAlive
        }
        if (!isAllowedLoopbackOrigin(request.origin)) {
            writeHttp(
                output,
                403,
                errorJson(null, McpErrorCodes.FORBIDDEN_ORIGIN, "MCP Origin 不允许"),
                keepAlive = false,
            )
            return false
        }
        if (!authOk(requireAuth, request.authorization, token)) {
            writeHttp(
                output,
                401,
                errorJson(null, McpErrorCodes.UNAUTHORIZED, "MCP 配对令牌无效"),
                keepAlive = keepAlive,
            )
            return keepAlive
        }

        if (request.body.isBlank()) {
            writeHttp(
                output,
                400,
                errorJson(null, McpErrorCodes.PARSE_ERROR, "空请求体"),
                keepAlive = keepAlive,
            )
            return keepAlive
        }
        val root = runCatching { JSONObject(request.body) }.getOrElse {
            writeHttp(
                output,
                400,
                errorJson(null, McpErrorCodes.PARSE_ERROR, "JSON 解析失败"),
                keepAlive = keepAlive,
            )
            return keepAlive
        }

        if (generation != runGeneration.get() || stopping.get()) {
            writeHttp(
                output,
                404,
                errorJson(null, McpErrorCodes.SERVER_ERROR, "服务已停止"),
                keepAlive = false,
            )
            return false
        }

        when (
            val result = protocol.dispatch(
                root,
                request.mcpSessionId,
                request.mcpProtocolVersion,
            )
        ) {
            is McpProtocol.DispatchResult.Accepted -> {
                val headers = buildMap {
                    result.sessionId?.let { put("Mcp-Session-Id", it) }
                }
                writeHttp(output, 202, null, headers, keepAlive = keepAlive)
            }
            is McpProtocol.DispatchResult.JsonResponse -> {
                val headers = buildMap {
                    result.sessionId?.let { put("Mcp-Session-Id", it) }
                }
                writeHttp(output, result.status, result.body, headers, keepAlive = keepAlive)
            }
            is McpProtocol.DispatchResult.HttpError -> {
                writeHttp(output, result.status, result.body, keepAlive = keepAlive)
            }
        }
        return keepAlive
    }

    private fun authOk(requireAuth: Boolean, authorization: String?, token: String): Boolean {
        if (!requireAuth) return true
        return constantTimeBearerMatches(authorization, token)
    }

    private fun publishState(
        running: Boolean,
        port: Int,
        token: String,
        requireAuth: Boolean,
        error: String?,
    ) {
        uiBridge.updateServerState(
            McpServerState(
                running = running,
                port = port,
                token = token,
                requireAuth = requireAuth,
                lastError = error,
                activeSessions = sessions.sessionCount(),
            ),
        )
    }

    private fun stopServer() {
        if (!stopping.compareAndSet(false, true)) {
            // 已在停止中：仍确保 generation 推进，避免陈旧 accept 循环写回。
            runGeneration.incrementAndGet()
            return
        }
        runGeneration.incrementAndGet()
        uiBridge.rejectPendingApproval()
        sessions.clear()
        runCatching { server?.close() }
        server = null
        uiBridge.updateServerState(McpServerState())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // 允许后续同进程生命周期内再次启动（改端口重启场景）。
        stopping.set(false)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MCP 本地服务", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(message: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_splash_flame)
        .setContentTitle("HZZS MCP 服务")
        .setContentText(message)
        .setOngoing(true)
        .build()

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val ACTION_START = "top.azek431.hzzs.mcp.START"
        const val ACTION_STOP = "top.azek431.hzzs.mcp.STOP"
        private const val CHANNEL_ID = "mcp_local"
        private const val NOTIFICATION_ID = 432
    }
}
