package top.azek431.hzzs.mcp.executor

import org.json.JSONArray
import org.json.JSONObject
import top.azek431.hzzs.data.vision.VisionRuntimeController
import top.azek431.hzzs.domain.vision.VisionResult
import top.azek431.hzzs.mcp.ok
import top.azek431.hzzs.mcp.toJson

import javax.inject.Inject

/**
 * 运行时控制执行器：视觉启停 / 状态 / 指标 / 诊断。
 *
 * 纯数据面（[top.azek431.hzzs.data.vision.VisionRuntimeController] 是视觉运行时唯一所有者，帧循环由其协调），
 * 不直接持有 SettingsRepository 写设置。
 */
class RuntimeControlExecutor @Inject constructor(
    private val runtime: VisionRuntimeController,
) : ToolExecutor {
    override val toolNames: Set<String> = setOf(
        "start_analysis",
        "stop_analysis",
        "restart_analysis",
        "cancel_actions",
        "get_status",
        "get_runtime_snapshot",
        "get_metrics",
        "run_diagnostics",
    )

    private val processStartedElapsedRealtimeMs: Long = android.os.SystemClock.elapsedRealtime()

    override suspend fun execute(tool: String, arguments: JSONObject): JSONObject = when (tool) {
        "start_analysis" -> {
            runtime.start()
            ok("已请求启动分析")
        }
        "stop_analysis" -> {
            runtime.stop()
            ok("分析已停止")
        }
        "restart_analysis" -> {
            runtime.stop()
            runtime.start()
            ok("已请求重启分析")
        }
        "cancel_actions" -> {
            runtime.cancelPendingActions()
            ok("已取消在飞自动操作")
        }
        "get_status" -> runtime.status.value.toJson()
        "get_runtime_snapshot" -> runtimeSnapshot()
        "get_metrics" -> metricsJson()
        "run_diagnostics" -> JSONObject().apply {
            put("status", runtime.status.value.toJson())
            put("nativeLoaded", top.azek431.hzzs.nativevision.NativeVision.isAvailable)
            put("debugFrameCount", 0)
        }
        else -> throw IllegalArgumentException("未知工具：$tool")
    }

    private fun metricsJson(): JSONObject {
        val jvmRuntime = Runtime.getRuntime()
        val status = runtime.status.value
        val uptime = android.os.SystemClock.elapsedRealtime() - processStartedElapsedRealtimeMs
        return JSONObject().apply {
            put(
                "memory",
                JSONObject()
                    .put("totalBytes", jvmRuntime.totalMemory())
                    .put("freeBytes", jvmRuntime.freeMemory())
                    .put("usedBytes", jvmRuntime.totalMemory() - jvmRuntime.freeMemory())
                    .put("maxBytes", jvmRuntime.maxMemory()),
            )
            put(
                "frame",
                JSONObject()
                    .put("fps", status.fps.toDouble())
                    .put("processingMs", status.processingMs.toDouble())
                    .put("obstacleCount", status.obstacleCount)
                    .put("last30Fps", JSONArray().put(status.fps.toDouble()))
                    .put("last30ProcessingMs", JSONArray().put(status.processingMs.toDouble())),
            )
            put("uptimeMs", uptime.coerceAtLeast(0L))
            put("processStartedElapsedRealtimeMs", processStartedElapsedRealtimeMs)
        }
    }

    private suspend fun runtimeSnapshot(): JSONObject {
        val status = runtime.status.value
        val latest = runtime.latestResult.value
        return JSONObject().apply {
            put("status", status.toJson())
            put("latest", latest?.toJson() ?: JSONObject.NULL)
            put(
                "latestSummary",
                latest?.let { r ->
                    JSONObject().apply {
                        put("scene", r.scene.name)
                        put("sceneConfidence", r.sceneConfidence.toDouble())
                        put("detectionCount", r.detections.size)
                        put(
                            "kindHistogram",
                            JSONObject().apply {
                                r.detections.groupingBy { it.kind.name }.eachCount().forEach { (k, v) ->
                                    put(k, v)
                                }
                            },
                        )
                        put(
                            "hasPlayer",
                            r.detections.any {
                                it.kind == top.azek431.hzzs.domain.vision.ObjectKind.PLAYER
                            },
                        )
                    }
                } ?: JSONObject.NULL,
            )
            put("selectedScene", status.activeScene.name)
            put("captureBackend", status.activeBackend.name)
            put("overlayVisible", status.overlayVisible)
            put("activeGestureBackend", status.activeGestureBackend.name)
        }
    }
}

internal fun VisionResult.toJson(): JSONObject = JSONObject().apply {
    put("scene", scene.name)
    put("sceneConfidence", sceneConfidence.toDouble())
    put("processingNanos", processingNanos)
    put("detections", JSONArray(detections.map { detectionToJson(it) }))
}

private fun detectionToJson(d: top.azek431.hzzs.domain.vision.Detection): JSONObject = JSONObject().apply {
    put("kind", d.kind.name)
    put("confidence", d.confidence.toDouble())
    put("bounds", JSONObject().apply {
        put("left", d.bounds.left.toDouble())
        put("top", d.bounds.top.toDouble())
        put("right", d.bounds.right.toDouble())
        put("bottom", d.bounds.bottom.toDouble())
    })
    put("actionable", d.actionable)
    put("avoidance", d.avoidance.name)
}
