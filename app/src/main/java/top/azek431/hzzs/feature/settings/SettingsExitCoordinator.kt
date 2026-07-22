package top.azek431.hzzs.feature.settings

/**
 * 在应用级导航壳与设置模块之间转发“离开设置”意图。
 *
 * 导航壳不持有设置草稿；设置模块仍是 dirty 状态和保存/丢弃决策的唯一所有者。
 * 若导航请求早于设置界面完成挂载，协调器会暂存最后一次请求，避免绕过确认。
 * 线程：仅由 Compose 主线程调用。
 */
class SettingsExitCoordinator {
    private var interceptor: (((() -> Unit)) -> Unit)? = null
    private var pendingAction: (() -> Unit)? = null

    /** 注册设置模块的离开拦截器，并接管挂载前到达的最后一次导航请求。 */
    fun attach(interceptor: ((() -> Unit)) -> Unit): Registration {
        this.interceptor = interceptor
        pendingAction?.let { action ->
            pendingAction = null
            interceptor(action)
        }
        return Registration(this, interceptor)
    }

    /** 请求在设置模块完成保存或丢弃决策后执行 [action]。 */
    fun request(action: () -> Unit) {
        val current = interceptor
        if (current == null) {
            pendingAction = action
        } else {
            current(action)
        }
    }

    private fun detach(expected: ((() -> Unit)) -> Unit) {
        if (interceptor === expected) interceptor = null
    }

    /** 只移除创建本注册对象的拦截器，避免旧 Composition 卸载时清除新实例。 */
    class Registration internal constructor(
        private val owner: SettingsExitCoordinator,
        private val interceptor: ((() -> Unit)) -> Unit,
    ) {
        fun dispose() {
            owner.detach(interceptor)
        }
    }
}
