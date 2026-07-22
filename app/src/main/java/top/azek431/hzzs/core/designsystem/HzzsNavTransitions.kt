package top.azek431.hzzs.core.designsystem

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * 一级目的地等同层切换：Material fade-through 风格（淡入淡出，无大幅位移）。
 * 减少动效时为 None。
 */
fun HzzsMotionPolicy.fadeThroughEnter(): EnterTransition {
    if (!enabled) return EnterTransition.None
    return fadeIn(animationSpec = tween(durationMillis = transitionMs))
}

fun HzzsMotionPolicy.fadeThroughExit(): ExitTransition {
    if (!enabled) return ExitTransition.None
    return fadeOut(animationSpec = tween(durationMillis = exitMs))
}

/**
 * 设置分类等明确前后层级：短 shared-axis X（水平滑入 + 淡变）。
 * 减少动效时退化为无动画。
 */
fun HzzsMotionPolicy.sharedAxisXEnter(): EnterTransition {
    if (!enabled) return EnterTransition.None
    val offsetPx = { full: Int -> (full * 0.08f).toInt().coerceAtLeast(1) }
    return slideInHorizontally(
        animationSpec = tween(durationMillis = transitionMs),
        initialOffsetX = offsetPx,
    ) + fadeIn(animationSpec = tween(durationMillis = transitionMs))
}

fun HzzsMotionPolicy.sharedAxisXExit(): ExitTransition {
    if (!enabled) return ExitTransition.None
    val offsetPx = { full: Int -> -(full * 0.04f).toInt().coerceAtLeast(1) }
    return slideOutHorizontally(
        animationSpec = tween(durationMillis = exitMs),
        targetOffsetX = offsetPx,
    ) + fadeOut(animationSpec = tween(durationMillis = exitMs))
}

fun HzzsMotionPolicy.sharedAxisXPopEnter(): EnterTransition {
    if (!enabled) return EnterTransition.None
    val offsetPx = { full: Int -> -(full * 0.08f).toInt().coerceAtLeast(1) }
    return slideInHorizontally(
        animationSpec = tween(durationMillis = transitionMs),
        initialOffsetX = offsetPx,
    ) + fadeIn(animationSpec = tween(durationMillis = transitionMs))
}

fun HzzsMotionPolicy.sharedAxisXPopExit(): ExitTransition {
    if (!enabled) return ExitTransition.None
    val offsetPx = { full: Int -> (full * 0.04f).toInt().coerceAtLeast(1) }
    return slideOutHorizontally(
        animationSpec = tween(durationMillis = exitMs),
        targetOffsetX = offsetPx,
    ) + fadeOut(animationSpec = tween(durationMillis = exitMs))
}
