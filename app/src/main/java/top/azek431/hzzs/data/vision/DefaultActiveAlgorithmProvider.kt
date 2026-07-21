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
 * 进程级活动算法快照。线程安全：激活在互斥下完成，读取无锁。
 *
 * 不解析 Zip/JSON 文件；调用方在算法安装层完成声明式解析后传入已构造的 profile。
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

    override fun current(): AlgorithmActivation = active.get()

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
