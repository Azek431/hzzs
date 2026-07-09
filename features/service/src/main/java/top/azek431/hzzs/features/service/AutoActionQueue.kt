// 火崽崽助手（HZZS）自动操作服务 — 操作队列管理器。
//
// 职责：
// - 管理排队中的操作列表（FIFO 顺序执行）
// - 提供线程安全的入队/出队/清空接口
// - 管理暂停/开关状态
// - 管理操作执行延迟配置
//
// 设计原因：
// - 队列逻辑独立封装，便于测试和扩展
// - 静态方法供外部调用（HUDRenderer 入队、设置界面开关）
// - 队列操作使用 synchronized 保护，防止并发修改
//
// 与 AutoOperationService 的关系：
// - AutoActionQueue 负责队列管理和状态控制
// - AutoOperationService 负责生命周期管理和定时调度
// - GestureInjector 负责将队列中的操作转换为实际触摸事件
//
// 线程安全：
// - 队列操作（enqueue/dequeue/clear）使用 synchronized 保护
// - 状态变量（isEnabled/isPaused/delayMs）使用 @Volatile 保证可见性
// - 所有公共方法都是静态的，可在任意线程调用

package top.azek431.hzzs.features.service

import android.os.SystemClock
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * 自动操作队列管理器。
 *
 * 管理排队中的操作列表，提供线程安全的入队/出队/清空接口。
 * 所有公共方法都是静态的，供 AutoOperationService 和 HUDRenderer 调用。
 *
 * 队列行为：
 * - 入队：如果自动操作已禁用或已暂停，直接丢弃不入队
 * - 出队：FIFO 顺序，每次取出队列头部的一个操作
 * - 清空：禁用时自动清空队列，防止残留操作被执行
 */
object AutoActionQueue {

    private const val TAG = "HZZS-AutoOp"

    // ==================== 状态 ====================

    /** 排队中的操作列表，按 FIFO 顺序执行 */
    private val queue = mutableListOf<QueuedAction>()

    /** 是否暂停（不影响开关状态，暂停时跳过队列处理） */
    @Volatile
    private var isPaused = false

    /** 自动操作总开关：false 时所有入队请求被丢弃 */
    @Volatile
    private var isEnabled = false

    /** 操作执行间隔（毫秒），范围 0~500，默认 100ms */
    @Volatile
    private var delayMs = 100

    // ==================== 队列操作 ====================

    /**
     * 将操作加入队列。
     *
     * 如果自动操作已禁用或已暂停，直接丢弃不入队。
     * 队列操作使用 synchronized 保护，确保线程安全。
     *
     * @param action 要排队的操作
     */
    fun enqueue(action: QueuedAction) {
        // 快速退出：如果开关关闭或已暂停，直接丢弃
        if (!isEnabled || isPaused) return

        synchronized(queue) {
            queue.add(action)
            Log.d(TAG, "[Queue] enqueued ${action.type}, size=${queue.size}")
        }
    }

    /**
     * 从队列中取出下一个操作并移除。
     *
     * @return 下一个操作，或 null（队列为空）
     */
    fun dequeue(): QueuedAction? {
        synchronized(queue) {
            if (queue.isEmpty()) return null
            val action = queue.removeAt(0)
            Log.d(TAG, "[Queue] dequeued ${action.type}, remaining=${queue.size}")
            return action
        }
    }

    /**
     * 清空队列中的所有操作。
     *
     * 通常在自动操作被禁用时调用，防止残留操作被执行。
     */
    fun clear() {
        synchronized(queue) {
            queue.clear()
            Log.d(TAG, "[Queue] cleared")
        }
    }

    // ==================== 状态管理 ====================

    /**
     * 设置自动操作开关。
     *
     * 禁用时自动清空队列，防止残留操作被执行。
     *
     * @param enabled 是否启用
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) clear()
        Log.i(TAG, "[Queue] enabled=$enabled")
    }

    /**
     * 设置暂停状态。
     *
     * 暂停不影响开关状态——已入队的操作会在恢复后继续执行，
     * 但新操作会被丢弃。
     *
     * @param paused 是否暂停
     */
    fun setPaused(paused: Boolean) {
        isPaused = paused
        Log.d(TAG, "[Queue] paused=$paused")
    }

    /**
     * 设置操作执行延迟。
     *
     * @param delayMs 延迟毫秒数（自动限制在 0~500 范围内）
     */
    fun setDelay(delayMs: Int) {
        this.delayMs = max(0, min(delayMs, 500))
        Log.d(TAG, "[Queue] delay=${this.delayMs}ms")
    }

    /** 获取当前队列大小（调试用） */
    fun size(): Int = synchronized(queue) { queue.size }

    /** 获取当前延迟配置 */
    fun getDelay(): Int = delayMs

    /** 获取当前队列状态（调试用） */
    fun getStatus(): String {
        val size = synchronized(queue) { queue.size }
        return "enabled=$isEnabled, paused=$isPaused, delay=${delayMs}ms, queue=$size"
    }
}

/**
 * 排队中的操作数据结构。
 *
 * 由 HUDRenderer 检测到动作提示时创建，包含操作类型、目标坐标和时间戳。
 * 坐标使用归一化值（0.0 ~ 1.0），执行时转换为屏幕像素坐标。
 *
 * @property type 操作类型（JUMP=跳跃, SLIDE=滑铲, DOUBLE_JUMP=二连跳, TAP=点击）
 * @property targetX 目标 X 坐标（归一化 0~1）
 * @property targetY 目标 Y 坐标（归一化 0~1）
 * @property timestamp 入队时间戳（SystemClock.uptimeMillis）
 */
data class QueuedAction(
    val type: ActionType,
    val targetX: Float,
    val targetY: Float,
    val timestamp: Long = SystemClock.uptimeMillis(),
) {
    /** 操作类型枚举 */
    enum class ActionType {
        /** 跳跃 — 应对蛋糕断层 */
        JUMP,
        /** 滑铲 — 应对悬垂裱花袋 */
        SLIDE,
        /** 二连跳 — 应对宽断层 */
        DOUBLE_JUMP,
        /** 点击 — 通用点击操作 */
        TAP,
    }
}
