package top.azek431.hzzs.platform.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.azek431.hzzs.core.model.CaptureBackend

/**
 * 回归：Android 10 等 API&lt;30 设备上强制/选择无障碍截图不得硬启动失败路径。
 * 通过注入 isSupported，避免 JVM 单测依赖真实 Build.VERSION。
 */
class CaptureBackendResolutionTest {

    private val api29Support: (CaptureBackend) -> Boolean = { backend ->
        when (backend) {
            CaptureBackend.ACCESSIBILITY -> false
            else -> true
        }
    }

    private val api30Support: (CaptureBackend) -> Boolean = { _ -> true }

    @Test
    fun followUserBackendWhenSupported() {
        val resolution = resolveEffectiveCaptureBackend(
            captureBackend = CaptureBackend.MEDIA_PROJECTION,
            developerEnabled = false,
            forceCaptureBackend = CaptureBackend.ACCESSIBILITY,
            isSupported = api29Support,
        )
        assertEquals(CaptureBackend.MEDIA_PROJECTION, resolution.effective)
        assertEquals(CaptureBackend.MEDIA_PROJECTION, resolution.requested)
        assertFalse(resolution.fellBack)
        assertNull(resolution.fallbackReason)
    }

    @Test
    fun forceAccessibilityFallsBackOnApi29() {
        val resolution = resolveEffectiveCaptureBackend(
            captureBackend = CaptureBackend.MEDIA_PROJECTION,
            developerEnabled = true,
            forceCaptureBackend = CaptureBackend.ACCESSIBILITY,
            isSupported = api29Support,
        )
        assertEquals(CaptureBackend.ACCESSIBILITY, resolution.requested)
        assertEquals(CaptureBackend.MEDIA_PROJECTION, resolution.effective)
        assertTrue(resolution.fellBack)
        val reason = requireNotNull(resolution.fallbackReason)
        assertTrue(reason.contains("Android 11"))
        assertTrue(reason.contains("MEDIA_PROJECTION"))
    }

    @Test
    fun forceAccessibilityFallsBackToUserAutoWhenMainIsSupported() {
        val resolution = resolveEffectiveCaptureBackend(
            captureBackend = CaptureBackend.AUTO,
            developerEnabled = true,
            forceCaptureBackend = CaptureBackend.ACCESSIBILITY,
            isSupported = api29Support,
        )
        assertEquals(CaptureBackend.ACCESSIBILITY, resolution.requested)
        assertEquals(CaptureBackend.AUTO, resolution.effective)
        assertTrue(resolution.fellBack)
    }

    @Test
    fun forceAccessibilityKeepsOnApi30() {
        val resolution = resolveEffectiveCaptureBackend(
            captureBackend = CaptureBackend.AUTO,
            developerEnabled = true,
            forceCaptureBackend = CaptureBackend.ACCESSIBILITY,
            isSupported = api30Support,
        )
        assertEquals(CaptureBackend.ACCESSIBILITY, resolution.effective)
        assertFalse(resolution.fellBack)
    }

    @Test
    fun forceIgnoredWhenDeveloperDisabled() {
        val resolution = resolveEffectiveCaptureBackend(
            captureBackend = CaptureBackend.AUTO,
            developerEnabled = false,
            forceCaptureBackend = CaptureBackend.ACCESSIBILITY,
            isSupported = api29Support,
        )
        assertEquals(CaptureBackend.AUTO, resolution.effective)
        assertFalse(resolution.fellBack)
    }

    @Test
    fun userSelectedAccessibilityFallsBackOnApi29() {
        val resolution = resolveEffectiveCaptureBackend(
            captureBackend = CaptureBackend.ACCESSIBILITY,
            developerEnabled = false,
            forceCaptureBackend = null,
            isSupported = api29Support,
        )
        assertEquals(CaptureBackend.ACCESSIBILITY, resolution.requested)
        assertEquals(CaptureBackend.MEDIA_PROJECTION, resolution.effective)
        assertTrue(resolution.fellBack)
    }

    @Test
    fun neverEscalatesAutoToAccessibility() {
        val resolution = resolveEffectiveCaptureBackend(
            captureBackend = CaptureBackend.AUTO,
            developerEnabled = true,
            forceCaptureBackend = null,
            isSupported = api29Support,
        )
        assertEquals(CaptureBackend.AUTO, resolution.effective)
        assertFalse(resolution.fellBack)
    }
}
