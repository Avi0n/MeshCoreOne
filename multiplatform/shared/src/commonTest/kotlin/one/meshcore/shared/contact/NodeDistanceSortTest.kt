package one.meshcore.shared.contact

import kotlin.test.Test
import kotlin.test.assertEquals

class NodeDistanceSortTest {
    @Test
    fun distancePutsLocatedRowsFirstThenNearestThenName() {
        val here = UserLocation(latitude = 0.0, longitude = 0.0)
        val sorted = listOf(row("far", 10.0), row("near", 1.0), row("zeta"), row("alpha"))
            .sortedWith(
                distanceThenNameComparator(
                    userLocation = here,
                    hasLocation = { it.distance != null },
                    distanceMeters = { item, _ -> item.distance!! },
                    byName = compareBy { it.name },
                ),
            )
            .map { it.name }
        assertEquals(listOf("near", "far", "alpha", "zeta"), sorted)
    }

    @Test
    fun distanceWithoutALocationSortsByNameOnly() {
        val sorted = listOf(row("b", 1.0), row("a", 9.0))
            .sortedWith(
                distanceThenNameComparator(
                    userLocation = null,
                    hasLocation = { it.distance != null },
                    distanceMeters = { _, _ -> error("distance is not read without a location") },
                    byName = compareBy { it.name },
                ),
            )
            .map { it.name }
        assertEquals(listOf("a", "b"), sorted)
    }

    @Test
    fun hopsSortKnownCountsFirstThenFallBack() {
        val sorted = listOf(row("unknown"), row("three", hops = 3), row("one", hops = 1), row("other", hops = 1))
            .sortedWith(
                hopsThenComparator(
                    hops = { it.hops },
                    fallback = compareBy { it.name },
                ),
            )
            .map { it.name }
        assertEquals(listOf("one", "other", "three", "unknown"), sorted)
    }

    private data class Row(val name: String, val distance: Double? = null, val hops: Int? = null)

    private fun row(name: String, distance: Double? = null, hops: Int? = null) = Row(name, distance, hops)
}
