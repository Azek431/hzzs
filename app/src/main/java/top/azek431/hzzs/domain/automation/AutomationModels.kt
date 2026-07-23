package top.azek431.hzzs.domain.automation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import top.azek431.hzzs.domain.vision.Avoidance

/**
 * 自动操作领域模型（纯 Kotlin）。
 *
 * 职责：
 * - 描述归一化手势与动作任务
 * - 串行仲裁系统手势，防止并发 `dispatchGesture`
 * - 记录已提交 track，做空间短时去重
 *
 * 安全：本包只做规则与门控数据结构，不直接调用无障碍 API。
 * 真正注入手势由 `service.automation` 完成。
 */

/**
 * 归一化手势规格。
 *
 * 坐标为全屏 `[0, 1]`。`endX/endY` 同时为空表示点击；
 * 同时有值表示滑动。时长限制在 10ms..1000ms。
 */
data class GestureSpec(
    val startX: Float,
    val startY: Float,
    val endX: Float? = null,
    val endY: Float? = null,
    val durationMs: Long = 30L,
    /**
     * 非滑动手势时，额外的双击延迟（毫秒）。
     * 用于海盐脚本中"press 两次间隔 60ms"的模式。
     */
    val doublePressDelayMs: Long = 0L,
) {
    init {
        require(startX in 0f..1f && startY in 0f..1f)
        require(endX == null || endX in 0f..1f)
        require(endY == null || endY in 0f..1f)
        require((endX == null) == (endY == null))
        require(durationMs in 10L..1_000L)
        require(doublePressDelayMs in 0L..2_000L)
    }
}

/**
 * 一次待执行的自动操作任务。
 *
 * @property id 动作唯一 ID（用于回执匹配）
 * @property trackId Tracker 稳定 ID；成功提交后进入账本，避免重复规划
 * @property avoidance 规避类型，决定手势形态
 * @property createdAtUptimeMs / expiresAtUptimeMs 基于 `SystemClock.uptimeMillis` 的 TTL
 * @property allowedPackages 包名白名单；分发前仍须再校验前台包
 * @property requiredWindowClassPrefixes 可选窗口类前缀约束
 * @property retryCount 已重试次数（由运行时递增）
 */
data class AutomationAction(
    val id: Long,
    val trackId: Long,
    val avoidance: Avoidance,
    val gesture: GestureSpec,
    val createdAtUptimeMs: Long,
    val expiresAtUptimeMs: Long,
    val allowedPackages: Set<String>,
    val requiredWindowClassPrefixes: Set<String> = emptySet(),
    val retryCount: Int = 0,
) {
    init {
        require(id > 0)
        require(trackId > 0)
        require(createdAtUptimeMs <= expiresAtUptimeMs)
        require(allowedPackages.isNotEmpty())
        require(retryCount >= 0)
    }

    /** 空前缀集合表示不限制窗口类；否则任一前缀匹配即可。 */
    fun matchesWindow(className: String): Boolean =
        requiredWindowClassPrefixes.isEmpty() || requiredWindowClassPrefixes.any(className::startsWith)
}

/** 手势分发终态。 */
enum class DispatchOutcome {
    /** 系统确认完成 */
    COMPLETED,
    /** 超时或系统取消 */
    CANCELLED,
    /** 前置条件失败或回执不匹配 */
    REJECTED,
    /** 到达过期时间未发出 */
    EXPIRED,
}

/**
 * 分发回执。
 *
 * 仲裁器要求回执中的 action id / trackId 与请求一致，防止串单。
 */
data class DispatchReceipt(
    val action: AutomationAction,
    val outcome: DispatchOutcome,
    val detail: String? = null,
)

/** 平台手势注入抽象；测试可替换为假实现。 */
fun interface GestureDispatcher {
    suspend fun dispatch(action: AutomationAction): DispatchReceipt
}

/**
 * 系统手势的唯一串行闸门。
 *
 * 线程：`dispatch` 在持锁期间等待系统回执或超时，
 * 保证同一时刻最多一个 `dispatchGesture` 在飞。
 *
 * @param clock 单调时钟（通常 `SystemClock.uptimeMillis`）
 * @param dispatcher 真正注入手势的平台适配
 * @param dispatchTimeoutMs 等待回执超时，超时记为 [DispatchOutcome.CANCELLED]
 */
class GestureArbiter(
    private val clock: () -> Long,
    private val dispatcher: GestureDispatcher,
    private val dispatchTimeoutMs: Long = 1_500L,
) {
    init { require(dispatchTimeoutMs in 100L..10_000L) }

    private val mutex = Mutex()

    /**
     * 串行分发动作。
     *
     * 顺序：过期检查 → 调用 dispatcher（带超时）→ 校验回执身份。
     */
    suspend fun dispatch(action: AutomationAction): DispatchReceipt = mutex.withLock {
        if (clock() >= action.expiresAtUptimeMs) {
            return@withLock DispatchReceipt(action, DispatchOutcome.EXPIRED, "动作已过期")
        }
        val receipt = runCatching {
            withTimeoutOrNull(dispatchTimeoutMs) { dispatcher.dispatch(action) }
                ?: DispatchReceipt(action, DispatchOutcome.CANCELLED, "手势回调超时")
        }.getOrElse { error ->
            DispatchReceipt(action, DispatchOutcome.REJECTED, error.message ?: error.javaClass.simpleName)
        }
        if (receipt.action.id != action.id || receipt.action.trackId != action.trackId) {
            return@withLock DispatchReceipt(action, DispatchOutcome.REJECTED, "手势回执与请求不匹配")
        }
        receipt
    }
}

/**
 * 动作提交账本：跨帧去重。
 *
 * - track 维度：成功完成的 track 不再规划
 * - 空间维度：同一空间键在冷却窗口内不重复规划
 *
 * 场景 / 算法切换时应 [reset]。
 */
class ActionCommitLedger {
    private val mutex = Mutex()
    private val completedTracks = mutableSetOf<Long>()
    /** 空间去重键 → 最近成功时间；短时间内同位置不再规划。 */
    private val recentSpatialKeys = mutableMapOf<String, Long>()

    /**
     * 是否允许为该 track / 空间位置规划新动作。
     *
     * @param spatialKey 可空；为空时只检查 track
     * @param nowMs 与写入时同一时钟基准
     */
    suspend fun canPlan(trackId: Long, spatialKey: String? = null, nowMs: Long = 0L): Boolean =
        mutex.withLock {
            if (trackId <= 0 || trackId in completedTracks) return@withLock false
            if (spatialKey != null) {
                val previous = recentSpatialKeys[spatialKey]
                if (previous != null && nowMs - previous < SPATIAL_COOLDOWN_MS) return@withLock false
            }
            true
        }

    /**
     * 根据回执提交。仅 [DispatchOutcome.COMPLETED] 写入去重集合。
     */
    suspend fun commit(receipt: DispatchReceipt, spatialKey: String? = null) = mutex.withLock {
        if (receipt.outcome == DispatchOutcome.COMPLETED) {
            completedTracks += receipt.action.trackId
            if (spatialKey != null) {
                recentSpatialKeys[spatialKey] = receipt.action.createdAtUptimeMs
            }
        }
    }

    /** 清空全部去重状态。 */
    suspend fun reset() = mutex.withLock {
        completedTracks.clear()
        recentSpatialKeys.clear()
    }

    private companion object {
        /** 同位置成功动作后的冷却毫秒数。 */
        const val SPATIAL_COOLDOWN_MS = 700L
    }
}
