package top.azek431.hzzs.mcp.executor

import org.json.JSONObject
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.core.preferences.hardenedForExternalIngest
import top.azek431.hzzs.mcp.McpEventBus
import top.azek431.hzzs.mcp.McpProfileStore
import top.azek431.hzzs.mcp.ok
import top.azek431.hzzs.mcp.requireString

import javax.inject.Inject

class ProfileExecutor @Inject constructor(
    private val profileStore: McpProfileStore,
    private val settings: SettingsRepository,
) : ToolExecutor {
    override val toolNames: Set<String> = setOf(
        "save_profile",
        "load_profile",
        "list_profiles",
        "delete_profile",
    )

    override suspend fun execute(tool: String, arguments: JSONObject): JSONObject = when (tool) {
        "save_profile" -> {
            val name = arguments.requireString("name")
            val description = arguments.optString("description")
            val meta = profileStore.save(name, description, settings.current())
            ok("profile 已保存：${meta.name}").put("profile", meta.toJson())
        }
        "load_profile" -> {
            val name = arguments.requireString("name")
            val persist = arguments.optBoolean("persist", false)
            val loaded = profileStore.load(name)
            val config = loaded.hardenedForExternalIngest(settings.current())
            if (persist) settings.save(config) else settings.preview(config)
            runCatching {
                McpEventBus.append(
                    McpEventBus.Type.CONFIG_CHANGE,
                    JSONObject()
                        .put("source", "load_profile")
                        .put("name", name)
                        .put("persist", persist),
                )
            }
            ok(if (persist) "profile 已永久应用：$name" else "profile 已预览：$name")
                .put("name", name)
        }
        "list_profiles" -> {
            val metas = profileStore.list()
            JSONObject()
                .put("profiles", org.json.JSONArray(metas.map { it.toJson() }))
                .put("count", metas.size)
        }
        "delete_profile" -> {
            val name = arguments.requireString("name")
            val deleted = profileStore.delete(name)
            ok(if (deleted) "profile 已删除：$name" else "profile 不存在：$name")
                .put("deleted", deleted)
        }
        else -> throw IllegalArgumentException("未知工具：$tool")
    }
}

private fun McpProfileStore.Meta.toJson(): JSONObject = JSONObject()
    .put("name", name)
    .put("description", description)
    .put("createdAtEpochMs", createdAtEpochMs)
    .put("updatedAtEpochMs", updatedAtEpochMs)
    .put("sizeBytes", sizeBytes)
    .put("schemaVersion", schemaVersion)
