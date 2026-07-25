package top.azek431.hzzs.mcp

import android.content.Context
import android.content.ContextWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.preferences.ConfigJson
import top.azek431.hzzs.core.preferences.validated
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用内命名配置 Profile 存储。
 *
 * 职责：
 * - 将当前 [AppConfig] 快照保存为命名 profile（filesDir/mcp-profiles/<name>.json），
 *   供 `save_profile` / `load_profile` 等 MCP 工具复用
 * - 不参与 DataStore 自动保存 / 迁移；纯 MCP 通道
 *
 * 文件格式：`{name, description, createdAtEpochMs, updatedAtEpochMs, config: AppConfigJSON}`。
 * 边界：名仅 `[A-Za-z0-9_-]{1,64}`；数量上限 32（新建超限拒绝，不静默淘汰）；单文件 ≤1MB。
 * 线程：读写经 [Mutex] 互斥。
 */
@Singleton
class McpProfileStore @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    /**
     * 测试入口：注入临时 filesDir，不依赖 Robolectric / Instrumentation。
     * 仅覆盖 [Context.getFilesDir]；其它 Context API 不可用。
     */
    internal constructor(filesDir: File) : this(FilesDirContext(filesDir))

    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val mutex = Mutex()

    data class Meta(
        val name: String,
        val description: String,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
        val sizeBytes: Long,
        val schemaVersion: Int,
    )

    private fun File.profileFile(name: String) = File(this, "$name.json")

    /** 保存（覆盖）profile。新建时若已达上限则拒绝。 */
    suspend fun save(name: String, description: String, config: AppConfig): Meta = mutex.withLock {
        validateName(name)
        check(directory.exists() || directory.mkdirs()) { "无法创建 profile 目录" }
        val file = directory.profileFile(name)
        val existed = file.isFile
        if (!existed) {
            val count = directory.listFiles().orEmpty().count { it.isFile && it.extension.equals("json", true) }
            require(count < MAX_PROFILES) { "profile 数量已达上限 $MAX_PROFILES，请先删除旧 profile" }
        }
        val now = System.currentTimeMillis()
        val createdAt = if (existed) {
            runCatching {
                JSONObject(file.readText()).optLong("createdAtEpochMs", file.lastModified())
            }.getOrDefault(file.lastModified())
        } else {
            now
        }
        val payload = JSONObject().apply {
            put("name", name)
            put("description", description)
            put("createdAtEpochMs", createdAt)
            put("updatedAtEpochMs", now)
            put("config", JSONObject(ConfigJson.encode(config)))
        }.toString()
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "profile 过大（>${MAX_FILE_BYTES} 字节）" }
        file.writeBytes(bytes)
        Meta(name, description, createdAt, now, bytes.size.toLong(), config.schemaVersion)
    }

    /** 读取 profile 为 [AppConfig]；未知名抛 [IllegalArgumentException]。 */
    suspend fun load(name: String): AppConfig = mutex.withLock {
        validateName(name)
        val file = directory.profileFile(name)
        require(file.isFile) { "profile 不存在：$name" }
        require(file.length() <= MAX_FILE_BYTES) { "profile 过大：$name" }
        val root = JSONObject(file.readText())
        val configObj = root.optJSONObject("config")
            ?: error("profile 格式错误（缺少 config）：$name")
        ConfigJson.decode(configObj.toString()).validated()
    }

    /** 列出 profile 元数据（按名字典序）。 */
    suspend fun list(): List<Meta> = mutex.withLock {
        directory.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .mapNotNull { file ->
                runCatching {
                    if (file.length() > MAX_FILE_BYTES) return@runCatching null
                    val obj = JSONObject(file.readText())
                    Meta(
                        name = obj.optString("name").ifBlank { file.nameWithoutExtension },
                        description = obj.optString("description"),
                        createdAtEpochMs = obj.optLong("createdAtEpochMs", file.lastModified()),
                        updatedAtEpochMs = obj.optLong("updatedAtEpochMs", file.lastModified()),
                        sizeBytes = file.length(),
                        schemaVersion = obj.optJSONObject("config")?.optInt("schemaVersion")
                            ?: AppConfig.CURRENT_SCHEMA,
                    )
                }.getOrNull()
            }
            .sortedBy { it.name }
            .toList()
    }

    /** 删除 profile；仅当文件存在且删除成功时返回 true。 */
    suspend fun delete(name: String): Boolean = mutex.withLock {
        validateName(name)
        val file = directory.profileFile(name)
        file.isFile && file.delete()
    }

    /** profile 数量。 */
    suspend fun count(): Int = mutex.withLock {
        directory.listFiles().orEmpty().count { it.isFile && it.extension.equals("json", true) }
    }

    private fun validateName(name: String) {
        require(NAME_PATTERN.matches(name)) {
            "profile 名仅支持 [A-Za-z0-9_-]{1,64}：$name"
        }
    }

    /** 仅实现 [getFilesDir] 的最小 Context，供 JVM 单测。 */
    private class FilesDirContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    private companion object {
        const val DIRECTORY_NAME = "mcp-profiles"
        const val MAX_PROFILES = 32
        const val MAX_FILE_BYTES = 1024 * 1024
        val NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
