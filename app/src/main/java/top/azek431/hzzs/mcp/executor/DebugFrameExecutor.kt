package top.azek431.hzzs.mcp.executor

import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.data.vision.DebugFrameRecorder
import top.azek431.hzzs.mcp.ok
import top.azek431.hzzs.mcp.requireString

import javax.inject.Inject

/**
 * 调试帧执行器：元数据 / 清理 / 读取（base64 JPEG）/ 强制存帧。
 *
 * 全部工具需开发者选项；`get_debug_frame` / `capture_debug_frame` 额外需 `mcp.allowDebugFrames`
 * 并标记 HIGH_RISK（涉及屏幕内容落盘 / 读取）。
 */
class DebugFrameExecutor @Inject constructor(
    private val debugFrames: DebugFrameRecorder,
    private val settings: SettingsRepository,
) : ToolExecutor {
    override val toolNames: Set<String> = setOf(
        "list_debug_frames",
        "clear_debug_frames",
        "get_debug_frame",
        "capture_debug_frame",
    )

    override suspend fun execute(tool: String, arguments: JSONObject): JSONObject = when (tool) {
        "list_debug_frames" -> debugFrameMetadata()
        "clear_debug_frames" -> ok("已清除 ${debugFrames.clear()} 个调试帧")
        "get_debug_frame" -> executeGetDebugFrame(arguments)
        "capture_debug_frame" -> {
            ensureDeveloper()
            debugFrames.requestCapture()
            ok("已请求存下一帧（将写入调试帧目录，受 MAX_FILES 上限裁剪）")
        }
        else -> throw IllegalArgumentException("未知工具：$tool")
    }

    private suspend fun executeGetDebugFrame(arguments: JSONObject): JSONObject {
        ensureDeveloper()
        val mcp = settings.current().mcp
        check(mcp.allowDebugFrames) { "请先开启 MCP 允许读取调试帧（mcp.allowDebugFrames）" }
        val name = arguments.requireString("name")
        val maxWidth = arguments.optInt("maxWidth", 480).coerceIn(64, 1080)
        val quality = arguments.optInt("quality", 70).coerceIn(10, 100)
        val bytes = debugFrames.getBytes(name, maxWidth, quality)
        check(bytes != null) { "调试帧不存在或文件名非法：$name" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return JSONObject()
            .put("name", name)
            .put("width", bounds.outWidth.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("height", bounds.outHeight.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("sizeBytes", bytes.size)
            .put("maxWidth", maxWidth)
            .put("quality", quality)
            .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private suspend fun debugFrameMetadata(): JSONObject {
        val config = settings.current()
        check(config.developer.enabled && config.mcp.allowDebugFrames) {
            "请先在开发者设置中允许 MCP 读取调试帧元数据"
        }
        return JSONObject().put(
            "frames",
            org.json.JSONArray().apply {
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

    private suspend fun ensureDeveloper() {
        check(settings.current().developer.enabled) {
            "请先开启开发者选项（set_developer_enabled 或关于页连点版本号）"
        }
    }
}
