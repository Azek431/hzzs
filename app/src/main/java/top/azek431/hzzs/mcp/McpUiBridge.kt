package top.azek431.hzzs.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程级 UI 桥：前台服务与 Compose 之间的审批、导航与运行时状态通道。
 *
 * 线程：StateFlow 可跨线程写；审批 Deferred 用互斥保护，超时默认拒绝。
 * 取消：调用方可在外层 Job 取消时中断 [requestApproval]；超时/拒绝 fail-closed。
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

    /**
     * 请求用户确认一次写操作。
     * @return true 仅当用户在超时前明确批准；超时/并发冲突/取消均为 false。
     */
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

    /** 服务停止或会话失效时拒绝挂起审批，避免断连后仍执行副作用。 */
    fun rejectPendingApproval() {
        synchronized(approvalMutex) {
            approvalDeferred?.complete(false)
            approvalDeferred = null
            mutableApproval.value = null
        }
    }

    fun requestNavigation(route: String) {
        mutableNavigation.value = route
    }

    fun consumeNavigation(route: String) {
        if (mutableNavigation.value == route) mutableNavigation.value = null
    }

    private companion object {
        const val APPROVAL_TIMEOUT_MS = 60_000L
    }
}
