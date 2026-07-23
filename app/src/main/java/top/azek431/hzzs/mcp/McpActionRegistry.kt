package top.azek431.hzzs.mcp

import org.json.JSONArray
import org.json.JSONObject
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.McpPermissionLevel
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.core.preferences.hardenedForExternalIngest
import top.azek431.hzzs.data.vision.DebugFrameRecorder
import top.azek431.hzzs.data.vision.VisionRuntimeController
import javax.inject.Inject
import javax.inject.Singleton

/** 协议层可调用的动作面（便于 JVM 单测注入假实现）。 */
interface McpActionSurface {
    suspend fun readResource(uri: String): JSONObject
    suspend fun call(
        tool: String,
        arguments: JSONObject,
        session: McpSessionManager.Session?,
    ): JSONObject
}

/**
 * 应用内动作面：MCP 与后续自动化测试共用。
 *
 * 安全：
 * - 所有写操作经 [authorize]，按四级权限门控。
 * - TRUSTED_SESSION 仅在当前 [McpSessionManager.Session] 已 initialized 时放行普通写；
 *   高风险工具仍拒绝，需 FULL_ACCESS。
 * - 完整访问仍不能绕过系统权限对话框。
 */
@Singleton
class McpActionRegistry @Inject constructor(
    private val settings: SettingsRepository,
    private val runtime: VisionRuntimeController,
    private val uiBridge: McpUiBridge,
    private val debugFrames: DebugFrameRecorder,
) : McpActionSurface {
    override suspend fun readResource(uri: String): JSONObject = when (uri) {
        "app://status" -> runtime.status.value.toJson()
        "app://settings/current" -> JSONObject(settings.exportJson(settings.snapshot()))
        "app://settings/schema" -> JSONObject().apply {
            put("schemaVersion", AppConfig.CURRENT_SCHEMA)
            put(
                "captureBackends",
                JSONArray(top.azek431.hzzs.core.model.CaptureBackend.entries.map { it.name }),
            )
            put(
                "permissionLevels",
                JSONArray(McpPermissionLevel.entries.map { it.name }),
            )
        }
        "app://vision/latest" -> runtime.latestResult.value?.toJson() ?: JSONObject.NULL.asObject()
        "app://vision/metrics" -> runtime.status.value.toJson()
        "app://debug/frames" -> debugFrameMetadata()
        else -> throw IllegalArgumentException("未知资源：$uri")
    }

    override suspend fun call(
        tool: String,
        arguments: JSONObject,
        session: McpSessionManager.Session?,
    ): JSONObject {
        val descriptor = McpToolCatalog.tool(tool)
            ?: throw IllegalArgumentException("未知工具：$tool")
        validateArguments(descriptor, arguments)
        val level = settings.snapshot().mcp.permissionLevel
        authorize(descriptor, arguments, level, session)
        return execute(descriptor.name, arguments)
    }

    private fun validateArguments(descriptor: McpToolDescriptor, arguments: JSONObject) {
        descriptor.required.forEach { key ->
            if (!arguments.has(key) || arguments.isNull(key)) {
                throw IllegalArgumentException("缺少参数：$key")
            }
            val value = arguments.opt(key)
            if (value is String && value.isBlank()) {
                throw IllegalArgumentException("参数不能为空：$key")
            }
        }
        // 拒绝 schema 未声明的键（与 additionalProperties:false 对齐）
        val allowed = descriptor.inputSchema.optJSONObject("properties")
            ?.keys()
            ?.asSequence()
            ?.toSet()
            .orEmpty()
        val keys = arguments.keys().asSequence().toSet()
        val unknown = keys - allowed
        if (unknown.isNotEmpty()) {
            throw IllegalArgumentException("未知参数：${unknown.joinToString()}")
        }
    }

    private suspend fun execute(tool: String, arguments: JSONObject): JSONObject = when (tool) {
        "get_status" -> runtime.status.value.toJson()
        "get_settings" -> JSONObject(settings.exportJson(settings.snapshot()))
        "preview_settings" -> {
            val config = ingestMcpConfig(arguments.requireString("config"))
            settings.preview(config)
            ok("已临时预览设置（权限型字段已按安全策略收敛）")
        }
        "save_settings" -> {
            val config = ingestMcpConfig(arguments.requireString("config"))
            settings.save(config)
            ok("设置已保存（权限型字段已按安全策略收敛）")
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

    /**
     * MCP 写入的配置相对当前已保存 baseline 做外部摄入硬化：
     * 不得静默开启自动操作、不得自提 MCP 权限级、不得强行升权截图后端。
     */
    private suspend fun ingestMcpConfig(rawJson: String): AppConfig {
        val baseline = settings.snapshot()
        val imported = settings.importJson(rawJson)
        return imported.hardenedForExternalIngest(baseline)
    }

    /**
     * 按当前 MCP 权限级门控写操作；只读工具直接放行。
     * TRUSTED_SESSION 必须绑定已 initialized 的内存会话，禁止把「信任」当持久特权。
     */
    private suspend fun authorize(
        descriptor: McpToolDescriptor,
        arguments: JSONObject,
        level: McpPermissionLevel,
        session: McpSessionManager.Session?,
    ) {
        if (descriptor.risk == McpToolRisk.READ) return
        when (level) {
            McpPermissionLevel.READ_ONLY -> error("MCP 当前为只读模式")
            McpPermissionLevel.ASK_EVERY_TIME -> {
                val approved = uiBridge.requestApproval(descriptor.name, summarize(descriptor.name, arguments))
                check(approved) { "用户未批准操作" }
            }
            McpPermissionLevel.TRUSTED_SESSION -> {
                if (session == null || !session.initialized) {
                    error("信任会话无效：请重新 initialize 并完成 initialized 握手")
                }
                if (descriptor.risk == McpToolRisk.HIGH_RISK) {
                    error("该操作需要完整访问权限")
                }
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
        "navigate" -> "AI 请求打开应用页面：${arguments.optString("route")}"
        "set_overlay_visible" -> "AI 请求更改悬浮窗显示状态"
        "clear_debug_frames" -> "AI 请求清除本机调试帧"
        else -> "AI 请求执行：$tool（${arguments.length()} 个参数）"
    }
}

internal fun ok(message: String) = JSONObject().put("ok", true).put("message", message)

internal fun top.azek431.hzzs.core.model.RuntimeStatus.toJson() = JSONObject().apply {
    put("running", running)
    put("captureReady", captureReady)
    put("overlayVisible", overlayVisible)
    put("overlayBlockReason", overlayBlockReason?.name ?: JSONObject.NULL)
    put("activeScene", activeScene.name)
    put("activeBackend", activeBackend.name)
    put("fps", fps.toDouble())
    put("processingMs", processingMs.toDouble())
    put("obstacleCount", obstacleCount)
    lastError?.let { put("lastError", it) }
}

internal fun top.azek431.hzzs.domain.vision.VisionResult.toJson() = JSONObject().apply {
    put("scene", scene.name)
    put("sceneConfidence", sceneConfidence.toDouble())
    put("processingNanos", processingNanos)
    put(
        "detections",
        JSONArray().apply {
            detections.forEach { detection ->
                put(
                    JSONObject().apply {
                        put("kind", detection.kind.name)
                        put("confidence", detection.confidence.toDouble())
                        put("left", detection.bounds.left.toDouble())
                        put("top", detection.bounds.top.toDouble())
                        put("right", detection.bounds.right.toDouble())
                        put("bottom", detection.bounds.bottom.toDouble())
                    },
                )
            }
        },
    )
}

private fun Any?.asObject(): JSONObject = JSONObject().put("value", this)
