package top.azek431.hzzs.mcp

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Streamable HTTP 会话表。
 *
 * - 会话仅存在于 MCP 服务进程生命周期，**不**持久化。
 * - [McpPermissionLevel.TRUSTED_SESSION] 的「信任」绑定具体 sessionId，服务重启后失效。
 * - generation 令牌：服务启停后旧会话全部作废。
 */
class McpSessionManager {
    data class Session(
        val id: String,
        val protocolVersion: String,
        val createdAtMs: Long,
        @Volatile var initialized: Boolean = false,
        @Volatile var lastAccessMs: Long = createdAtMs,
        @Volatile var clientName: String? = null,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val activeConnections = AtomicInteger(0)
    @Volatile private var generation: Long = 0L

    fun currentGeneration(): Long = generation

    fun bumpGeneration() {
        generation += 1L
        sessions.clear()
    }

    fun tryAcquireConnection(): Boolean {
        while (true) {
            val current = activeConnections.get()
            if (current >= McpLimits.MAX_CONCURRENT_CONNECTIONS) return false
            if (activeConnections.compareAndSet(current, current + 1)) return true
        }
    }

    fun releaseConnection() {
        activeConnections.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun activeConnectionCount(): Int = activeConnections.get()

    fun sessionCount(): Int = sessions.size

    fun createSession(protocolVersion: String, clientName: String?): Session {
        pruneExpired()
        require(sessions.size < McpLimits.MAX_SESSIONS) { "会话数已达上限" }
        val id = randomSessionId()
        val now = System.currentTimeMillis()
        val session = Session(
            id = id,
            protocolVersion = protocolVersion,
            createdAtMs = now,
            clientName = clientName,
        )
        sessions[id] = session
        return session
    }

    fun get(sessionId: String?): Session? {
        if (sessionId.isNullOrBlank()) return null
        val session = sessions[sessionId] ?: return null
        val now = System.currentTimeMillis()
        if (now - session.lastAccessMs > McpLimits.SESSION_IDLE_TTL_MS) {
            sessions.remove(sessionId)
            return null
        }
        session.lastAccessMs = now
        return session
    }

    fun markInitialized(sessionId: String): Boolean {
        val session = get(sessionId) ?: return false
        session.initialized = true
        return true
    }

    fun remove(sessionId: String?) {
        if (!sessionId.isNullOrBlank()) sessions.remove(sessionId)
    }

    fun clear() {
        sessions.clear()
        activeConnections.set(0)
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, session) ->
            now - session.lastAccessMs > McpLimits.SESSION_IDLE_TTL_MS
        }
    }

    private fun randomSessionId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
