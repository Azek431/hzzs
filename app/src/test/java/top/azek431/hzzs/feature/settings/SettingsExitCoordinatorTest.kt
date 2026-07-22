package top.azek431.hzzs.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsExitCoordinatorTest {
    @Test
    fun `attached interceptor controls when navigation executes`() {
        val coordinator = SettingsExitCoordinator()
        var pending: (() -> Unit)? = null
        var navigated = false
        val registration = coordinator.attach { action -> pending = action }

        coordinator.request { navigated = true }

        assertFalse(navigated)
        assertTrue(pending != null)
        pending?.invoke()
        assertTrue(navigated)
        registration.dispose()
    }

    @Test
    fun `request before attach is delivered after settings mounts`() {
        val coordinator = SettingsExitCoordinator()
        var intercepted = 0
        var navigated = false

        coordinator.request { navigated = true }
        val registration = coordinator.attach { action ->
            intercepted += 1
            action()
        }

        assertEquals(1, intercepted)
        assertTrue(navigated)
        registration.dispose()
    }

    @Test
    fun `disposing stale registration keeps newest interceptor active`() {
        val coordinator = SettingsExitCoordinator()
        var firstCalls = 0
        var secondCalls = 0
        val first = coordinator.attach { firstCalls += 1 }
        val second = coordinator.attach { action ->
            secondCalls += 1
            action()
        }

        first.dispose()
        var navigated = false
        coordinator.request { navigated = true }

        assertEquals(0, firstCalls)
        assertEquals(1, secondCalls)
        assertTrue(navigated)
        second.dispose()
    }

    @Test
    fun `latest pre-attach request replaces obsolete destination`() {
        val coordinator = SettingsExitCoordinator()
        val destinations = mutableListOf<String>()

        coordinator.request { destinations += "home" }
        coordinator.request { destinations += "about" }
        val registration = coordinator.attach { action -> action() }

        assertEquals(listOf("about"), destinations)
        registration.dispose()
    }
}
