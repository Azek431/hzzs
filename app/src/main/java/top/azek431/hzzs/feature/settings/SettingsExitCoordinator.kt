package top.azek431.hzzs.feature.settings

/**
 * 在应用级导航壳与设置模块之间转发“离开设置”意图。
 *
 * 即时保存模式下设置页不再拦截未保存草稿；协调器仅保证：
 * 1. 设置已挂载时，离开前先 [flush] 再导航；
 * 2. 若导航请求早于设置挂载，暂存最后一次请求，挂载后立即 flush+执行。
 *
 * 线程：仅由 Compose 主线程调用。
 */
class SettingsExitCoordinator {
    private var flusher: ((onDone: () -> Unit) -> Unit)? = null
    private var pendingAction: (() -> Unit)? = null

    /**
     * 注册设置模块的刷盘回调。
     * [flush] 应在配置落盘（或无挂起写）后调用 onDone。
     */
    fun attach(flush: (onDone: () -> Unit) -> Unit): Registration {
        this.flusher = flush
        pendingAction?.let { action ->
            pendingAction = null
            flush(action)
        }
        return Registration(this, flush)
    }

    /** 请求在设置模块完成刷盘后执行 [action]。未挂载时暂存。 */
    fun request(action: () -> Unit) {
        val current = flusher
        if (current == null) {
            pendingAction = action
        } else {
            current(action)
        }
    }

    private fun detach(expected: (onDone: () -> Unit) -> Unit) {
        if (flusher === expected) flusher = null
    }

    /** 只移除创建本注册对象的拦截器，避免旧 Composition 卸载时清除新实例。 */
    class Registration internal constructor(
        private val owner: SettingsExitCoordinator,
        private val flush: (onDone: () -> Unit) -> Unit,
    ) {
        fun dispose() {
            owner.detach(flush)
        }
    }
}
