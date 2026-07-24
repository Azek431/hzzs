package top.azek431.hzzs.platform.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.model.GestureBackend

/**
 * 手势后端 AUTO 解析与永不升 Root 不变量。
 */
class GestureBackendResolutionTest {

    @Test
    fun autoPrefersAccessibilityWhenConnected() {
        val r = resolveEffectiveGestureBackend(
            gestureBackend = GestureBackend.AUTO,
            accessibilityConnected = true,
            shizukuReady = true,
        )
        assertEquals(GestureBackend.AUTO, r.requested)
        assertEquals(GestureBackend.ACCESSIBILITY, r.effective)
        assertFalse(r.fellBack)
    }

    @Test
    fun autoUsesShizukuWhenA11yDownAndShizukuReady() {
        val r = resolveEffectiveGestureBackend(
            gestureBackend = GestureBackend.AUTO,
            accessibilityConnected = false,
            shizukuReady = true,
        )
        assertEquals(GestureBackend.SHIZUKU, r.effective)
        assertTrue(r.fellBack)
        assertTrue(r.fallbackReason.orEmpty().contains("Shizuku"))
    }

    @Test
    fun autoNeverResolvesToRoot() {
        val r = resolveEffectiveGestureBackend(
            gestureBackend = GestureBackend.AUTO,
            accessibilityConnected = false,
            shizukuReady = false,
        )
        assertNotEquals(GestureBackend.ROOT, r.effective)
        assertEquals(GestureBackend.ACCESSIBILITY, r.effective)
    }

    @Test
    fun explicitRootKept() {
        val r = resolveEffectiveGestureBackend(
            gestureBackend = GestureBackend.ROOT,
            accessibilityConnected = false,
            shizukuReady = false,
        )
        assertEquals(GestureBackend.ROOT, r.effective)
        assertFalse(r.fellBack)
    }

    @Test
    fun explicitShizukuKeptEvenIfA11yConnected() {
        val r = resolveEffectiveGestureBackend(
            gestureBackend = GestureBackend.SHIZUKU,
            accessibilityConnected = true,
            shizukuReady = false,
        )
        assertEquals(GestureBackend.SHIZUKU, r.effective)
    }

    @Test
    fun explicitAccessibilityKept() {
        val r = resolveEffectiveGestureBackend(
            gestureBackend = GestureBackend.ACCESSIBILITY,
            accessibilityConnected = false,
            shizukuReady = true,
        )
        assertEquals(GestureBackend.ACCESSIBILITY, r.effective)
    }
}
