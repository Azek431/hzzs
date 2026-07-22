package top.azek431.hzzs.mcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import top.azek431.hzzs.R
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.model.McpPermissionLevel
import top.azek431.hzzs.core.preferences.ConfigJson
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.data.vision.DebugFrameRecorder
import top.azek431.hzzs.data.vision.VisionRuntimeController
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** 开发者设置展示、并供复制到 Claude Code 配置的 MCP 运行时状态。 */
data class McpServerState(
    val running: Boolean = false,
    val port: Int = 0,
    val token: String = "",
    val lastError: String? = null,
)

/** 待用户确认的一次 MCP 写操作请求。 */
data class McpApprovalRequest(
    val id: Long,
    val tool: String,
    val summary: String,
)

/**
 * 进程级 UI 桥：前台服务与 Compose 之间的审批、导航与运行时状态通道。
 *
 * 线程：StateFlow 可跨线程写；审批 Deferred 用互斥保护，超时默认拒绝。
 */
@Singleton
class McpUiBridge @Inject constructor() {
    private val mutableServerState = MutableStateFlow(McpServerState())
    val serverState: StateFlow<McpServerState> = mutableServerState.asStateFlow()

    private val mutableApproval = MutableStateFlow<McpApprovalRequest?>(null)
    val approval: StateFlow<McpApprovalRequest?> = mutableApproval.asStateFlow()

    /** MCP 请求的语义路由；Compose 导航消费后清除。 */
    private val mutableNavigation = MutableStateFlow<String?>(null)
    val navigation: StateFlow<String?> = mutableNavigation.asStateFlow()

    private val approvalMutex = Any()
    private var approvalDeferred: CompletableDeferred<Boolean>? = null
    private var nextApprovalId = 1L

    fun updateServerState(state: McpServerState) {
        mutableServerState.value = state
    }

    suspend fun requestApproval(tool: String, summary: String): Boolean {
        val deferred = synchronized(approvalMutex) {
            if (approvalDeferred != null) return false
            CompletableDeferred<Boolean>().also {
                approvalDeferred = it
                mutableApproval.value = McpApprovalRequest(nextApprovalId++, tool, summary)
            }
        }
        return try {
            withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() } ?: false
        } finally {
            synchronized(approvalMutex) {
                if (approvalDeferred === deferred) approvalDeferred = null
                mutableApproval.value = null
            }
        }
    }

    fun resolveApproval(id: Long, approved: Boolean) {
        synchronized(approvalMutex) {
            if (mutableApproval.value?.id == id) approvalDeferred?.complete(approved)
        }
    }

    fun requestNavigation(route: String) {
        mutableNavigation.value = route
    }

    fun consumeNavigation(route: String) {
        if (mutableNavigation.value == route) mutableNavigation.value = null
    }

    private companion object { const val APPROVAL_TIMEOUT_MS = 60_000L }
}

/**
 * 应用内动作面：MCP 与后续自动化测试共用。
 *
 * 安全：
 * - 所有写操作经 [authorize]，按四级权限（只读 / 每次确认 / 会话信任 / 完整访问）门控。
 * - 传输层另用 loopback + 每服务生命周期随机 Bearer；完整访问仍不能绕过系统权限对话框。
 * - 高风险工具（如解锁自动操作）在会话信任级被拒绝，需完整访问。
 */
@Singleton
class McpActionRegistry @Inject constructor(
    private val settings: SettingsRepository,
    private val runtime: VisionRuntimeController,
    private val uiBridge: McpUiBridge,
    private val debugFrames: DebugFrameRecorder,
) {
    suspend fun readResource(uri: String): JSONObject = when (uri) {
        "app://status" -> runtime.status.value.toJson()
        "app://settings/current" -> JSONObject(settings.exportJson(settings.snapshot()))
        "app://settings/schema" -> JSONObject().apply {
            put("schemaVersion", top.azek431.hzzs.core.model.AppConfig.CURRENT_SCHEMA)
            put("captureBackends", JSONArray(top.azek431.hzzs.core.model.CaptureBackend.entries.map { it.name }))
            put("permissionLevels", JSONArray(McpPermissionLevel.entries.map { it.name }))
        }
        "app://vision/latest" -> runtime.latestResult.value?.toJson() ?: JSONObject.NULL.asObject()
        "app://vision/metrics" -> runtime.status.value.toJson()
        "app://debug/frames" -> debugFrameMetadata()
        else -> throw IllegalArgumentException("未知资源：$uri")
    }

    suspend fun call(tool: String, arguments: JSONObject): JSONObject {
        val write = tool !in READ_ONLY_TOOLS
        val level = settings.snapshot().mcp.permissionLevel
        authorize(tool, arguments, level, write)
        return when (tool) {
            "get_status" -> runtime.status.value.toJson()
            "get_settings" -> JSONObject(settings.exportJson(settings.snapshot()))
            "preview_settings" -> {
                val config = settings.importJson(arguments.requireString("config"))
                settings.preview(config)
                ok("已临时预览设置")
            }
            "save_settings" -> {
                val config = settings.importJson(arguments.requireString("config"))
                settings.save(config)
                ok("设置已保存")
            }
            "reset_preview" -> {
                settings.clearPreview()
                ok("临时预览已恢复")
            }
            "start_analysis" -> {
                runtime.start()
                ok("已请求启动分析")
            }
            "stop_analysis" -> {
                runtime.stop()
                ok("分析已停止")
            }
            "arm_automation" -> runtime.armAutomation().fold(
                onSuccess = { ok("自动操作已在当前会话解锁") },
                onFailure = { throw it },
            )
            "disarm_automation" -> {
                runtime.disarmAutomation()
                ok("自动操作已锁定")
            }
            "navigate" -> {
                val route = arguments.requireString("route")
                require(route in setOf("home", "runtime", "settings", "about")) { "未知页面：$route" }
                uiBridge.requestNavigation(route)
                ok("已请求打开 $route 页面")
            }
            "set_overlay_visible" -> {
                val current = settings.snapshot()
                settings.preview(
                    current.copy(
                        overlay = current.overlay.copy(enabled = arguments.optBoolean("enabled", true)),
                    ),
                )
                ok("悬浮窗显示状态已临时更新")
            }
            "run_diagnostics" -> JSONObject().apply {
                put("status", runtime.status.value.toJson())
                put("settingsValid", runCatching { settings.snapshot() }.isSuccess)
                put("nativeLoaded", top.azek431.hzzs.nativevision.NativeVision.isAvailable)
                put("debugFrameCount", runCatching { debugFrames.list().size }.getOrDefault(0))
            }
            "list_debug_frames" -> debugFrameMetadata()
            "clear_debug_frames" -> ok("已清除 ${debugFrames.clear()} 个调试帧")
            else -> throw IllegalArgumentException("未知工具：$tool")
        }
    }

    /**
     * 按当前 MCP 权限级门控写操作；只读工具直接放行。
     * 完整访问仅放开应用内能力，无法代替用户点击系统录屏/悬浮窗/无障碍对话框。
     */
    private suspend fun authorize(
        tool: String,
        arguments: JSONObject,
        level: McpPermissionLevel,
        write: Boolean,
    ) {
        if (!write) return
        when (level) {
            McpPermissionLevel.READ_ONLY -> error("MCP 当前为只读模式")
            McpPermissionLevel.ASK_EVERY_TIME -> {
                val approved = uiBridge.requestApproval(tool, summarize(tool, arguments))
                check(approved) { "用户未批准操作" }
            }
            McpPermissionLevel.TRUSTED_SESSION -> {
                check(tool !in HIGH_RISK_TOOLS) { "该操作需要完整访问权限" }
            }
            McpPermissionLevel.FULL_ACCESS -> Unit
        }
    }


    private suspend fun debugFrameMetadata(): JSONObject {
        val config = settings.snapshot()
        check(config.developer.enabled && config.mcp.allowDebugFrames) {
            "请先在开发者设置中允许 MCP 读取调试帧元数据"
        }
        return JSONObject().put(
            "frames",
            JSONArray().apply {
                debugFrames.list().forEach { frame ->
                    put(
                        JSONObject()
                            .put("name", frame.name)
                            .put("sizeBytes", frame.sizeBytes)
                            .put("modifiedAtEpochMs", frame.modifiedAtEpochMs),
                    )
                }
            },
        )
    }

    private fun summarize(tool: String, arguments: JSONObject): String = when (tool) {
        "save_settings" -> "AI 请求永久保存应用设置"
        "preview_settings" -> "AI 请求临时预览应用设置"
        "start_analysis" -> "AI 请求启动屏幕分析"
        "stop_analysis" -> "AI 请求停止屏幕分析"
        "arm_automation" -> "AI 请求解锁当前会话的自动操作"
        "navigate" -> "AI 请求打开应用页面：${arguments.optString("route")}"
        "set_overlay_visible" -> "AI 请求更改悬浮窗显示状态"
        "clear_debug_frames" -> "AI 请求清除本机调试帧"
        else -> "AI 请求执行：$tool（${arguments.length()} 个参数）"
    }

    private companion object {
        val READ_ONLY_TOOLS = setOf(
            "get_status",
            "get_settings",
            "run_diagnostics",
            "list_debug_frames",
        )
        val HIGH_RISK_TOOLS = setOf("arm_automation")
    }
}

/**
 * 最小子集的 MCP Streamable-HTTP JSON-RPC 前台服务，仅绑定 loopback。
 *
 * 安全边界：
 * - 不监听局域网；本机可通过 ADB `forward` 暴露给电脑上的 Claude Code。
 * - 每次启动生成随机 Bearer，恒时比较；Origin 仅允许空或 loopback。
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

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            return START_NOT_STICKY
        }
        if (server != null) return START_STICKY
        stopping.set(false)
        startForeground(NOTIFICATION_ID, notification("MCP 正在启动"))
        scope.launch { startServer() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServer()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startServer() {
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
        val token = randomToken()
        try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getLoopbackAddress(), config.port), 8)
            }
            server = socket
            // 不写 token 到日志；仅记录端口与权限级。
            AppLog.i("mcp", "MCP listening on 127.0.0.1:${config.port} level=${config.permissionLevel.name}")
            uiBridge.updateServerState(McpServerState(true, config.port, token))
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("127.0.0.1:${config.port} · ${config.permissionLevel.name}"),
            )
            while (!stopping.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                scope.launch { handle(client, token) }
            }
        } catch (error: Throwable) {
            AppLog.e("mcp", "MCP server failed: ${error.message}", error)
            uiBridge.updateServerState(McpServerState(lastError = error.message ?: error.javaClass.simpleName))
        } finally {
            stopServer()
        }
    }

    private suspend fun handle(socket: Socket, token: String) {
        socket.use { client ->
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val request = runCatching { readHttpRequest(input) }.getOrElse {
                writeHttp(output, 400, errorJson(null, -32700, "请求格式错误"))
                return
            }
            if (request.method == "GET" && request.path == "/health") {
                writeHttp(output, 200, JSONObject().put("status", "ok"))
                return
            }
            if (request.method != "POST" || request.path != "/mcp") {
                writeHttp(output, 404, errorJson(null, -32601, "接口不存在"))
                return
            }
            if (!isAllowedLoopbackOrigin(request.origin)) {
                writeHttp(output, 403, errorJson(null, -32002, "MCP Origin 不允许"))
                return
            }
            if (!constantTimeBearerMatches(request.authorization, token)) {
                writeHttp(output, 401, errorJson(null, -32001, "MCP 配对令牌无效"))
                return
            }
            val root = runCatching { JSONObject(request.body) }.getOrElse {
                writeHttp(output, 400, errorJson(null, -32700, "JSON 解析失败"))
                return
            }
            val response = processRpc(root)
            writeHttp(output, 200, response)
        }
    }

    private suspend fun processRpc(root: JSONObject): JSONObject {
        val id = root.opt("id")
        return runCatching {
            val method = root.optString("method")
            val params = root.optJSONObject("params") ?: JSONObject()
            val result = when (method) {
                "initialize" -> JSONObject().apply {
                    put("protocolVersion", "2025-06-18")
                    put("serverInfo", JSONObject().put("name", "HZZS").put("version", top.azek431.hzzs.BuildConfig.VERSION_NAME))
                    put("capabilities", JSONObject().put("tools", JSONObject()).put("resources", JSONObject()))
                }
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", tools())
                "tools/call" -> registry.call(
                    params.requireString("name"),
                    params.optJSONObject("arguments") ?: JSONObject(),
                ).let { JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", it.toString()))) }
                "resources/list" -> JSONObject().put("resources", resources())
                "resources/read" -> {
                    val uri = params.requireString("uri")
                    val value = registry.readResource(uri)
                    JSONObject().put("contents", JSONArray().put(JSONObject().put("uri", uri).put("mimeType", "application/json").put("text", value.toString())))
                }
                else -> throw IllegalArgumentException("不支持的方法：$method")
            }
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
        }.getOrElse { error -> errorJson(id, -32000, error.message ?: error.javaClass.simpleName) }
    }

    private fun tools(): JSONArray = JSONArray().apply {
        listOf(
            "get_status" to "读取运行状态",
            "get_settings" to "读取完整设置",
            "preview_settings" to "临时预览设置，离开设置页可恢复",
            "save_settings" to "永久保存设置",
            "reset_preview" to "清除临时预览",
            "start_analysis" to "启动屏幕分析",
            "stop_analysis" to "停止屏幕分析",
            "arm_automation" to "解锁当前会话自动操作",
            "disarm_automation" to "锁定自动操作",
            "navigate" to "打开应用内页面",
            "set_overlay_visible" to "临时显示或隐藏悬浮窗",
            "run_diagnostics" to "运行诊断",
            "list_debug_frames" to "列出私有目录中的调试帧元数据",
            "clear_debug_frames" to "清除私有目录中的调试帧",
        ).forEach { (name, description) ->
            put(JSONObject().apply {
                put("name", name)
                put("description", description)
                put("inputSchema", JSONObject().put("type", "object").put("additionalProperties", true))
            })
        }
    }

    private fun resources(): JSONArray = JSONArray().apply {
        listOf(
            "app://status",
            "app://settings/schema",
            "app://settings/current",
            "app://vision/latest",
            "app://vision/metrics",
            "app://debug/frames",
        ).forEach { uri ->
            put(JSONObject().put("uri", uri).put("name", uri.substringAfter("app://")).put("mimeType", "application/json"))
        }
    }

    private fun stopServer() {
        if (!stopping.compareAndSet(false, true)) return
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

private data class HttpRequest(
    val method: String,
    val path: String,
    val authorization: String?,
    val origin: String?,
    val body: String,
)

private fun readHttpRequest(input: BufferedInputStream): HttpRequest {
    val headerBytes = ArrayList<Byte>(1024)
    var matched = 0
    while (headerBytes.size < 16 * 1024) {
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
    lines.drop(1).forEach { line ->
        val index = line.indexOf(':')
        if (index <= 0) return@forEach
        when (line.substring(0, index).trim().lowercase()) {
            "content-length" -> length = line.substring(index + 1).trim().toInt()
            "authorization" -> authorization = line.substring(index + 1).trim()
            "origin" -> origin = line.substring(index + 1).trim()
        }
    }
    require(length in 0..MAX_BODY_BYTES) { "请求体过大" }
    val body = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val count = input.read(body, offset, length - offset)
        require(count > 0) { "请求体不完整" }
        offset += count
    }
    return HttpRequest(
        method = requestLine[0],
        path = requestLine[1],
        authorization = authorization,
        origin = origin,
        body = body.toString(Charsets.UTF_8),
    )
}

/**
 * 浏览器会带 Origin；CLI/ADB 通常省略。
 * 拒绝非 loopback Origin，防止 DNS rebinding 或跨源页面触达本机 MCP 桥。
 */
private fun isAllowedLoopbackOrigin(origin: String?): Boolean {
    if (origin.isNullOrBlank()) return true
    return runCatching {
        val uri = java.net.URI(origin)
        val host = uri.host?.lowercase() ?: return false
        uri.scheme in setOf("http", "https") &&
            (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]")
    }.getOrDefault(false)
}

/**
 * 校验 HTTP Authorization 头中的 Bearer 与服务端令牌。
 * 使用恒时比较，避免通过时序泄漏首个不匹配字符。
 */
private fun constantTimeBearerMatches(authorization: String?, token: String): Boolean {
    val provided = authorization?.removePrefix("Bearer ")
        ?.takeIf { authorization.startsWith("Bearer ") }
        ?: return false
    return MessageDigest.isEqual(
        provided.toByteArray(Charsets.UTF_8),
        token.toByteArray(Charsets.UTF_8),
    )
}

private fun writeHttp(output: BufferedOutputStream, status: Int, body: JSONObject) {
    val bytes = body.toString().toByteArray(Charsets.UTF_8)
    val phrase = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        else -> "Not Found"
    }
    output.write("HTTP/1.1 $status $phrase\r\n".toByteArray())
    output.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray())
    output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
    output.write("Connection: close\r\n\r\n".toByteArray())
    output.write(bytes)
    output.flush()
}

private fun errorJson(id: Any?, code: Int, message: String): JSONObject = JSONObject()
    .put("jsonrpc", "2.0")
    .put("id", id ?: JSONObject.NULL)
    .put("error", JSONObject().put("code", code).put("message", message))

private fun ok(message: String) = JSONObject().put("ok", true).put("message", message)

private fun JSONObject.requireString(name: String): String = optString(name).takeIf(String::isNotBlank)
    ?: throw IllegalArgumentException("缺少参数：$name")

private fun top.azek431.hzzs.core.model.RuntimeStatus.toJson() = JSONObject().apply {
    put("running", running)
    put("captureReady", captureReady)
    put("overlayVisible", overlayVisible)
    put("automationArmed", automationArmed)
    put("activeScene", activeScene.name)
    put("activeBackend", activeBackend.name)
    put("fps", fps.toDouble())
    put("processingMs", processingMs.toDouble())
    put("obstacleCount", obstacleCount)
    lastError?.let { put("lastError", it) }
}

private fun top.azek431.hzzs.domain.vision.VisionResult.toJson() = JSONObject().apply {
    put("scene", scene.name)
    put("sceneConfidence", sceneConfidence.toDouble())
    put("processingNanos", processingNanos)
    put("detections", JSONArray().apply {
        detections.forEach { detection ->
            put(JSONObject().apply {
                put("kind", detection.kind.name)
                put("confidence", detection.confidence.toDouble())
                put("left", detection.bounds.left.toDouble())
                put("top", detection.bounds.top.toDouble())
                put("right", detection.bounds.right.toDouble())
                put("bottom", detection.bounds.bottom.toDouble())
            })
        }
    })
}

private fun Any?.asObject(): JSONObject = JSONObject().put("value", this)
private const val MAX_BODY_BYTES = 256 * 1024
