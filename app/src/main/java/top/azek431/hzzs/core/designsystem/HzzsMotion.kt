package top.azek431.hzzs.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一动效策略：综合应用 [animationScale]/[reduceMotion] 与系统 animator 倍率。
 *
 * - 页面不得自行散落 duration/easing，应读 [LocalHzzsMotion]
 * - 动画倍率**不得**用于业务超时、帧龄或手势 TTL
 * - 减少动效 / 倍率为 0 时取消位移与缩放，允许极短或即时终态
 */
@Immutable
data class HzzsMotionPolicy(
    /** 是否播放位移/淡入淡出等过渡；false 时使用 [EnterTransition.None] 或 0ms。 */
    val enabled: Boolean,
    /** 应用配置的动画强度（已 clamp，reduceMotion 时为 0）。 */
    val appScale: Float,
    /** 系统 `animator_duration_scale`（缺失时按 1）。 */
    val systemScale: Float,
    /** 有效时长倍率 = app × system。 */
    val effectiveScale: Float,
    /** 交互反馈目标时长（已乘 effectiveScale，ms）。 */
    val interactionMs: Int,
    /** 同层页面/状态切换目标时长（ms）。 */
    val transitionMs: Int,
    /** 退出短于进入（ms）。 */
    val exitMs: Int,
    /** 层级进入的水平位移距离；减少动效时为 0。 */
    val sharedAxisOffset: Dp,
) {
    /** 将基准毫秒按有效倍率缩放；关闭动效时返回 0。 */
    fun scaleDuration(baseMs: Int): Int {
        if (!enabled || baseMs <= 0) return 0
        return (baseMs * effectiveScale).toInt().coerceAtLeast(1)
    }
}

/**
 * 由主题配置与系统倍率解析 [HzzsMotionPolicy]。
 * 纯函数，可供 JVM 单测；不读 Android Settings。
 */
object HzzsMotion {
    const val BASE_INTERACTION_MS = 120
    const val BASE_TRANSITION_MS = 200
    const val BASE_EXIT_MS = 150
    val BASE_SHARED_AXIS_OFFSET: Dp = 24.dp

    fun resolve(
        animationScale: Float,
        reduceMotion: Boolean,
        systemAnimatorDurationScale: Float = 1f,
    ): HzzsMotionPolicy {
        val appScale = if (reduceMotion) {
            0f
        } else {
            animationScale.coerceIn(0f, 2f)
        }
        val systemScale = systemAnimatorDurationScale.coerceAtLeast(0f)
        val effective = appScale * systemScale
        val enabled = effective > 0.001f
        return if (!enabled) {
            HzzsMotionPolicy(
                enabled = false,
                appScale = appScale,
                systemScale = systemScale,
                effectiveScale = 0f,
                interactionMs = 0,
                transitionMs = 0,
                exitMs = 0,
                sharedAxisOffset = 0.dp,
            )
        } else {
            HzzsMotionPolicy(
                enabled = true,
                appScale = appScale,
                systemScale = systemScale,
                effectiveScale = effective,
                interactionMs = (BASE_INTERACTION_MS * effective).toInt().coerceAtLeast(1),
                transitionMs = (BASE_TRANSITION_MS * effective).toInt().coerceAtLeast(1),
                exitMs = (BASE_EXIT_MS * effective).toInt().coerceAtLeast(1),
                sharedAxisOffset = BASE_SHARED_AXIS_OFFSET,
            )
        }
    }
}

val LocalHzzsMotion = staticCompositionLocalOf {
    HzzsMotion.resolve(animationScale = 1f, reduceMotion = false)
}
