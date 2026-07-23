package top.azek431.hzzs.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsExitCoordinatorTest {
    @Test
    fun `attached flusher runs before navigation`() {
        val coordinator = SettingsExitCoordinator()
        var flushed = false
        var navigated = false
        val registration = coordinator.attach { onDone ->
            flushed = true
            onDone()
        }

        coordinator.request { navigated = true }

        assertTrue(flushed)
        assertTrue(navigated)
        registration.dispose()
    }

    @Test
    fun `request before attach is delivered after settings mounts`() {
        val coordinator = SettingsExitCoordinator()
        var flushCount = 0
        var navigated = false

        coordinator.request { navigated = true }
        val registration = coordinator.attach { onDone ->
            flushCount += 1
            onDone()
        }

        assertEquals(1, flushCount)
        assertTrue(navigated)
        registration.dispose()
    }

    @Test
    fun `disposing stale registration keeps newest flusher active`() {
        val coordinator = SettingsExitCoordinator()
        var firstCalls = 0
        var secondCalls = 0
        val first = coordinator.attach { onDone ->
            firstCalls += 1
            onDone()
        }
        val second = coordinator.attach { onDone ->
            secondCalls += 1
            onDone()
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
        val registration = coordinator.attach { onDone -> onDone() }

        assertEquals(listOf("about"), destinations)
        registration.dispose()
    }

    @Test
    fun `request without attach only keeps last pending`() {
        val coordinator = SettingsExitCoordinator()
        var ran = ""
        coordinator.request { ran = "a" }
        coordinator.request { ran = "b" }
        assertEquals("", ran)
        coordinator.attach { onDone -> onDone() }
        assertEquals("b", ran)
    }
}
