package top.azek431.hzzs.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HzzsColorContrast] 对比度与 ARGB 规范化单测。 */
class HzzsColorContrastTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val gray = 0xFF777777.toInt()

    @Test
    fun blackOnWhiteExceedsAa() {
        val ratio = HzzsColorContrast.contrastRatio(black, white)
        assertTrue(ratio >= 20.0)
        assertTrue(HzzsColorContrast.meetsWcagAa(black, white))
    }

    @Test
    fun whiteOnBlackExceedsAa() {
        assertTrue(HzzsColorContrast.meetsWcagAa(white, black))
    }

    @Test
    fun grayOnGrayMayFailAa() {
        val ratio = HzzsColorContrast.contrastRatio(gray, gray)
        assertEquals(1.0, ratio, 0.01)
        assertFalse(HzzsColorContrast.meetsWcagAa(gray, gray))
    }

    @Test
    fun ensureOpaqueForcesAlpha() {
        val translucent = 0x80FF0000.toInt()
        val opaque = HzzsColorContrast.ensureOpaque(translucent)
        assertEquals(0xFF, (opaque ushr 24) and 0xFF)
        assertEquals(0xFF0000, opaque and 0x00FFFFFF)
    }

    @Test
    fun clampAlphaRespectsBounds() {
        val c = HzzsColorContrast.clampAlpha(0x10FFFFFF, minAlpha = 0x40, maxAlpha = 0xC0)
        assertEquals(0x40, (c ushr 24) and 0xFF)
        val high = HzzsColorContrast.clampAlpha(0xF0FFFFFF.toInt(), minAlpha = 0x40, maxAlpha = 0xC0)
        assertEquals(0xC0, (high ushr 24) and 0xFF)
    }

    @Test
    fun bestForegroundPicksHigherContrast() {
        val (fg, ratio) = HzzsColorContrast.bestForeground(white)
        assertEquals(black, fg)
        assertTrue(ratio >= 20.0)
        val (fgDark, _) = HzzsColorContrast.bestForeground(black)
        assertEquals(white, fgDark)
    }

    @Test
    fun compositeOverOpaqueFgReturnsFg() {
        val out = HzzsColorContrast.compositeOver(0xFFFF0000.toInt(), white)
        assertEquals(0xFFFF0000.toInt(), out)
    }

    @Test
    fun compositeHalfRedOverWhiteIsPinkish() {
        val halfRed = 0x80FF0000.toInt()
        val out = HzzsColorContrast.compositeOver(halfRed, white)
        val r = (out shr 16) and 0xFF
        val g = (out shr 8) and 0xFF
        assertTrue(r > 200)
        assertTrue(g > 100)
    }
}
