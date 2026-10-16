package one.meshcore.shared.pathedit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PathListEditTest {
    @Test
    fun removingAtDropsTheIndexAndIgnoresAMiss() {
        assertEquals(listOf("B"), listOf("A", "B").removingAt(0))
        assertNull(listOf("A").removingAt(5))
    }

    @Test
    fun movedUsesTheOffsetBeforeRemoval() {
        assertEquals(listOf("B", "A"), listOf("A", "B").moved(0, 2))
        assertEquals(listOf("A", "B"), listOf("B", "A").moved(1, 0))
        assertNull(listOf("A").moved(0, 3))
    }
}
