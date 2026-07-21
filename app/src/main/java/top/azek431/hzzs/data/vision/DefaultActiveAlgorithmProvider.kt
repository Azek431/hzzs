package top.azek431.hzzs.data.vision

import top.azek431.hzzs.domain.vision.ActiveAlgorithmProvider
import top.azek431.hzzs.domain.vision.AlgorithmActivation
import top.azek431.hzzs.domain.vision.AlgorithmProfileValidator
import top.azek431.hzzs.domain.vision.AlgorithmRuntimeProfile
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程级活动算法快照提供者。
 *
 * 职责：
 * - 持有当前 [AlgorithmActivation]（profile + generation + 回退标记 + 可选 loadError）；
 * - 激活前经 [AlgorithmProfileValidator] 校验；失败可按开关回退内置算法；
 * - 每次成功切换递增 [generation]，供帧循环检测算法变更并进入安全点。
 *
 * 线程：激活路径在 [lock] 互斥下完成；[current] 无锁读 [AtomicReference]。
 * 边界：不解析 Zip/JSON 文件；调用方（安装层）传入已构造的 [AlgorithmRuntimeProfile]。
 * 默认：进程启动即为内置 profile，usingBuiltinFallback=true。
 */
@Singleton
class DefaultActiveAlgorithmProvider @Inject constructor() : ActiveAlgorithmProvider {
    private val lock = Any()
    private val generationSeq = AtomicLong(0L)
    private val active = AtomicReference(
        AlgorithmActivation(
            profile = AlgorithmRuntimeProfile.builtin(),
            generation = generationSeq.incrementAndGet(),
            usingBuiltinFallback = true,
            loadError = null,
        ),
    )

    /** 无锁读取当前激活快照；返回值不可变，可安全跨线程观察。 */
    override fun current(): AlgorithmActivation = active.get()

    /**
     * 校验并激活算法 profile。
     *
     * @param profile 已解析的运行时配置
     * @param fallbackToBuiltinOnError true 时校验失败回退内置并 success（带 loadError）；false 时 failure 且不改快照意图由调用方处理
     * @return 新的 [AlgorithmActivation]；generation 单调递增
     */
    override fun activate(
        profile: AlgorithmRuntimeProfile,
        fallbackToBuiltinOnError: Boolean,
    ): Result<AlgorithmActivation> = synchronized(lock) {
        val validated = AlgorithmProfileValidator.validate(profile)
        if (validated.isSuccess) {
            val next = AlgorithmActivation(
                profile = validated.getOrThrow(),
                generation = generationSeq.incrementAndGet(),
                usingBuiltinFallback = profile.isBuiltin,
                loadError = null,
            )
            active.set(next)
            return Result.success(next)
        }
        val error = validated.exceptionOrNull()?.message?.take(240)
            ?: "算法配置校验失败"
        // fail-closed：不允许静默保留非法 profile。
        if (!fallbackToBuiltinOnError) {
            return Result.failure(IllegalArgumentException(error))
        }
        val fallback = AlgorithmActivation(
            profile = AlgorithmRuntimeProfile.builtin(),
            generation = generationSeq.incrementAndGet(),
            usingBuiltinFallback = true,
            loadError = error,
        )
        active.set(fallback)
        Result.success(fallback)
    }

    /**
     * 强制切回内置算法并推进 generation。
     *
     * @param reason 可选错误摘要（截断），写入 loadError 供 UI/诊断
     */
    override fun activateBuiltin(reason: String?): AlgorithmActivation = synchronized(lock) {
        val next = AlgorithmActivation(
            profile = AlgorithmRuntimeProfile.builtin(),
            generation = generationSeq.incrementAndGet(),
            usingBuiltinFallback = true,
            loadError = reason?.take(240),
        )
        active.set(next)
        next
    }
}
