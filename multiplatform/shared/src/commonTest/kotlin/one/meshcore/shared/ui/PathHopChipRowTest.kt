package one.meshcore.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PathHopChipRowTest {
    @Test
    fun pathDisplaySplitsOnArrowsAndTrims() {
        assertEquals(emptyList(), pathHopChipLabels(""))
        assertEquals(listOf("A3"), pathHopChipLabels("A3"))
        assertEquals(listOf("A3", "7F", "42"), pathHopChipLabels("A3 → 7F → 42"))
        assertEquals(listOf("A", "R"), pathHopChipLabels("A→R"))
    }

    @Test
    fun truncationKeepsEndsAndEllipsis() {
        val labels = listOf("A", "B", "C", "D", "E", "F", "G")
        assertEquals(labels, truncatedPathHopLabels(labels, threshold = 7, ends = 3))
        assertEquals(
            listOf("A", "B", "C", "…", "E", "F", "G"),
            truncatedPathHopLabels(labels, threshold = 6, ends = 3),
        )
    }
}
