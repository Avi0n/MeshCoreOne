package one.meshcore.shared.tools.ui

import one.meshcore.shared.tools.LosPointId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineOfSightPointToggleTest {
    @Test
    fun retappingTheSelectedPointClearsIt() {
        assertNull(toggledLosPoint(LosPointId.POINT_A, LosPointId.POINT_A))
        assertNull(toggledLosPoint(LosPointId.POINT_B, LosPointId.POINT_B))
        assertNull(toggledLosPoint(LosPointId.REPEATER, LosPointId.REPEATER))
    }

    @Test
    fun tappingADifferentPointSelectsIt() {
        assertEquals(LosPointId.POINT_A, toggledLosPoint(null, LosPointId.POINT_A))
        assertEquals(LosPointId.POINT_B, toggledLosPoint(LosPointId.POINT_A, LosPointId.POINT_B))
        assertEquals(LosPointId.REPEATER, toggledLosPoint(LosPointId.POINT_B, LosPointId.REPEATER))
        assertEquals(LosPointId.POINT_A, toggledLosPoint(LosPointId.REPEATER, LosPointId.POINT_A))
    }
}
