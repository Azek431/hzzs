package top.azek431.hzzs.service.automation

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import top.azek431.hzzs.core.model.GestureBackend
import top.azek431.hzzs.domain.automation.GestureDispatcher

/**
 * 按 [GestureBackend] 解析具体 [GestureDispatcher] 与前台探测。
 *
 * 安全：工厂本身不做能力探测；AUTO 须先经 [top.azek431.hzzs.platform.compat.resolveEffectiveGestureBackend]。
 */
interface GestureDispatcherFactory {
    fun dispatcher(backend: GestureBackend): GestureDispatcher

    /** 与 [dispatcher] 同源的前台快照；null = fail-closed。 */
    suspend fun snapshotForeground(backend: GestureBackend): ForegroundWindowSnapshot?

    /** 切换后端时清空 shell 前台缓存。 */
    fun clearShellCaches()
}

@Singleton
class DefaultGestureDispatcherFactory @Inject constructor(
    private val accessibility: AccessibilityGestureDispatcher,
    private val shizuku: ShizukuGestureDispatcher,
    private val root: RootGestureDispatcher,
) : GestureDispatcherFactory {
    override fun dispatcher(backend: GestureBackend): GestureDispatcher = when (backend) {
        // AUTO 应在调用前解析为 concrete；若误传 AUTO，fail-closed 走无障碍。
        GestureBackend.AUTO,
        GestureBackend.ACCESSIBILITY,
        -> accessibility
        GestureBackend.SHIZUKU -> shizuku
        GestureBackend.ROOT -> root
    }

    override suspend fun snapshotForeground(backend: GestureBackend): ForegroundWindowSnapshot? =
        when (backend) {
            GestureBackend.AUTO,
            GestureBackend.ACCESSIBILITY,
            -> AccessibilityForegroundProbe.snapshot()
            GestureBackend.SHIZUKU -> shizuku.snapshotForeground()
            GestureBackend.ROOT -> root.snapshotForeground()
        }

    override fun clearShellCaches() {
        shizuku.clearForegroundCache()
        root.clearForegroundCache()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class GestureBindings {
    @Binds
    abstract fun bindFactory(impl: DefaultGestureDispatcherFactory): GestureDispatcherFactory
}
