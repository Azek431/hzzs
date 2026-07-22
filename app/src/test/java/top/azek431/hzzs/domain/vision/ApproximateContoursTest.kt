package top.azek431.hzzs.domain.vision

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApproximateContoursTest {
    private val bounds = NormalizedRect(.20f, .25f, .80f, .90f)

    @Test
    fun everyObstacleContourStaysInsideAndPreservesExtremes() {
        ObjectKind.entries.filterNot { it == ObjectKind.PLAYER }.forEach { kind ->
            val contour = approximateDisplayContour(kind, bounds)
            assertTrue("kind=$kind", contour.size >= 4)
            contour.forEach { point ->
                assertTrue("kind=$kind x=${point.x}", point.x in bounds.left..bounds.right)
                assertTrue("kind=$kind y=${point.y}", point.y in bounds.top..bounds.bottom)
            }
            assertNear(bounds.left, contour.minOf { it.x }, kind)
            assertNear(bounds.top, contour.minOf { it.y }, kind)
            assertNear(bounds.right, contour.maxOf { it.x }, kind)
            assertNear(bounds.bottom, contour.maxOf { it.y }, kind)
        }
    }

    @Test
    fun displayContourDoesNotChangeActionGeometry() {
        val original = Detection(
            id = 7L,
            kind = ObjectKind.GREEN_BOTTLE,
            bounds = bounds,
            confidence = .91f,
            actionable = true,
            avoidance = Avoidance.DOUBLE_JUMP,
        )
        val decorated = original.withApproximateDisplayContour()
        assertEquals(original.id, decorated.id)
        assertEquals(original.kind, decorated.kind)
        assertEquals(original.bounds, decorated.bounds)
        assertEquals(original.actionable, decorated.actionable)
        assertEquals(original.avoidance, decorated.avoidance)
        assertTrue(decorated.displayContour.isNotEmpty())
    }

    @Test
    fun playerNeverReceivesDisplayContour() {
        val player = Detection(
            id = 1L,
            kind = ObjectKind.PLAYER,
            bounds = bounds,
            confidence = .88f,
            actionable = false,
        )
        assertTrue(player.withApproximateDisplayContour().displayContour.isEmpty())
        assertTrue(approximateDisplayContour(ObjectKind.PLAYER, bounds).isEmpty())
    }

    private fun assertNear(expected: Float, actual: Float, kind: ObjectKind) {
        assertTrue(
            "kind=$kind expected=$expected actual=$actual",
            abs(expected - actual) <= 1e-6f,
        )
    }
}
