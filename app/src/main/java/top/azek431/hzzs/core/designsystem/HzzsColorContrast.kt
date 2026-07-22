package top.azek431.hzzs.core.designsystem

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 颜色对比与 ARGB 规范化（纯 Kotlin，可 JVM 测试）。
 *
 * 供外观设置、主题导入与 HUD 共用；不依赖 Compose Color。
 * WCAG 2.x 相对亮度与对比度公式。
 */
object HzzsColorContrast {
    const val AA_NORMAL_TEXT = 4.5
    const val AA_LARGE_TEXT = 3.0

    /** 强制不透明（主题种子等角色）。 */
    fun ensureOpaque(argb: Int): Int = argb or 0xFF000000.toInt()

    /** 将 alpha 夹到 [minAlpha, maxAlpha]（0–255）。 */
    fun clampAlpha(argb: Int, minAlpha: Int, maxAlpha: Int): Int {
        val a = ((argb ushr 24) and 0xFF).coerceIn(minAlpha.coerceIn(0, 255), maxAlpha.coerceIn(0, 255))
        return (argb and 0x00FFFFFF) or (a shl 24)
    }

    /** sRGB 通道 → 线性。 */
    fun channelToLinear(c8: Int): Double {
        val c = (c8.coerceIn(0, 255) / 255.0)
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** 相对亮度 0–1（对 alpha 忽略，按不透明 RGB）。 */
    fun relativeLuminance(argb: Int): Double {
        val r = channelToLinear((argb shr 16) and 0xFF)
        val g = channelToLinear((argb shr 8) and 0xFF)
        val b = channelToLinear(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** 对比度 1–21。 */
    fun contrastRatio(foreground: Int, background: Int): Double {
        val l1 = relativeLuminance(ensureOpaque(foreground))
        val l2 = relativeLuminance(ensureOpaque(background))
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    fun meetsWcagAa(foreground: Int, background: Int, largeText: Boolean = false): Boolean {
        val need = if (largeText) AA_LARGE_TEXT else AA_NORMAL_TEXT
        return contrastRatio(foreground, background) >= need
    }

    /**
     * 在 [candidates] 中选与 [background] 对比度最高者；
     * 若均未达 AA，仍返回最高者（调用方决定是否阻止保存）。
     */
    fun bestForeground(
        background: Int,
        candidates: List<Int> = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
        largeText: Boolean = false,
    ): Pair<Int, Double> {
        require(candidates.isNotEmpty())
        var best = candidates.first()
        var bestRatio = contrastRatio(best, background)
        for (c in candidates.drop(1)) {
            val r = contrastRatio(c, background)
            if (r > bestRatio) {
                best = c
                bestRatio = r
            }
        }
        return best to bestRatio
    }

    /** 将 [fg] 以 [alpha] 叠在 [bg] 上，返回不透明合成色。 */
    fun compositeOver(fg: Int, bg: Int): Int {
        val fa = ((fg ushr 24) and 0xFF) / 255.0
        if (fa >= 0.999) return ensureOpaque(fg)
        if (fa <= 0.001) return ensureOpaque(bg)
        val ba = ((bg ushr 24) and 0xFF) / 255.0
        val fr = (fg shr 16) and 0xFF
        val fgG = (fg shr 8) and 0xFF
        val fb = fg and 0xFF
        val br = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bb = bg and 0xFF
        val outA = fa + ba * (1 - fa)
        fun mix(fc: Int, bc: Int): Int {
            val v = (fc * fa + bc * ba * (1 - fa)) / outA
            return v.toInt().coerceIn(0, 255)
        }
        val a = (outA * 255).toInt().coerceIn(0, 255)
        return (a shl 24) or (mix(fr, br) shl 16) or (mix(fgG, bgG) shl 8) or mix(fb, bb)
    }
}
