package top.azek431.hzzs.mcp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.preferences.ConfigJson
import top.azek431.hzzs.core.preferences.validated
import java.io.File
import java.util.concurrent.atomic.AtomicLong
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
 * 边界：名仅 `[A-Za-z0-9_-]{1,64}`；数量上限 32；单文件 ≤1MB；未知名抛 [IllegalArgumentException]。
 * 线程：写经 [Mutex] 互斥；读无竞争（文件内容不可变）。
 */
@Singleton
class McpProfileStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
    private val nextListToken = AtomicLong(0)

    data class Meta(
        val name: String,
        val description: String,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
        val sizeBytes: Long,
        val schemaVersion: Int,
    )

    private fun File.profileFile(name: String) = File(this, "$name.json")

    /** 保存（覆盖）profile。 */
    suspend fun save(name: String, description: String, config: AppConfig): Meta = mutex.withLock {
        validateName(name)
        check(directory.exists() || directory.mkdirs()) { "无法创建 profile 目录" }
        enforceCapacityLimit()
        val now = nextListToken.incrementAndGet().let { System.currentTimeMillis() }
        val file = directory.profileFile(name)
        val existed = file.exists()
        val createdAt = if (existed) file.lastModified() else now
        val payload = JSONObject().apply {
            put("name", name)
            put("description", description)
            put("createdAtEpochMs", createdAt)
            put("updatedAtEpochMs", now)
            put("config", JSONObject(ConfigJson.encode(config)))
        }.toString()
        val bytes = payload.toByteArray()
        require(bytes.size <= MAX_FILE_BYTES) { "profile 过大（>${MAX_FILE_BYTES} 字节）" }
        file.writeBytes(bytes)
        Meta(name, description, createdAt, now, bytes.size.toLong(), config.schemaVersion)
    }

    /** 读取 profile 为 [AppConfig]；未知名返回 null。 */
    suspend fun load(name: String): AppConfig = mutex.withLock {
        val file = directory.profileFile(name)
        require(file.isFile) { "profile 不存在：$name" }
        val decoded = ConfigJson.decode(file.readText())
        decoded.validated()
    }

    /** 列出 profile 元数据（按名字典序）。 */
    suspend fun list(): List<Meta> = mutex.withLock {
        directory.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .mapNotNull { file ->
                runCatching {
                    val obj = JSONObject(file.readText())
                    Meta(
                        name = obj.getString("name"),
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

    /** 删除 profile；返回是否成功删除。 */
    suspend fun delete(name: String): Boolean = mutex.withLock {
        validateName(name)
        val file = directory.profileFile(name)
        !file.exists() || file.delete()
    }

    /** profile 数量。 */
    suspend fun count(): Int = mutex.withLock {
        directory.listFiles().orEmpty().count { it.isFile }
    }

    private fun enforceCapacityLimit() {
        val files = directory.listFiles().orEmpty().filter { it.isFile }
        if (files.size < MAX_PROFILES) return
        val oldest = files.minByOrNull { it.lastModified() } ?: return
        oldest.delete()
    }

    private fun validateName(name: String) {
        require(NAME_PATTERN.matches(name)) {
            "profile 名仅支持 [A-Za-z0-9_-]{1,64}：$name"
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "mcp-profiles"
        const val MAX_PROFILES = 32
        const val MAX_FILE_BYTES = 1024 * 1024
        val NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
