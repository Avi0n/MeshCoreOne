package one.meshcore.shared.remotenode

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Neighbour-page aggregation: advancing offsets, total-reached stop, and empty-page stop. */
class NeighboursPaginationTest {
    @Test
    fun aggregatesPagesUntilTotalReached() = runTest {
        val pages = mapOf(
            0 to testNeighboursResponse(totalCount = 5, neighbours = listOf(testNeighbour(), testNeighbour())),
            2 to testNeighboursResponse(totalCount = 5, neighbours = listOf(testNeighbour(), testNeighbour())),
            4 to testNeighboursResponse(totalCount = 5, neighbours = listOf(testNeighbour())),
        )
        val offsets = mutableListOf<Int>()
        val result = NeighboursPagination.collectingAllPages { offset ->
            offsets += offset
            pages.getValue(offset)
        }
        assertEquals(listOf(0, 2, 4), offsets)
        assertEquals(5, result.neighbours.size)
        assertEquals(5, result.totalCount)
    }

    @Test
    fun stopsOnEmptyPageEvenIfBelowTotal() = runTest {
        val pages = mapOf(
            0 to testNeighboursResponse(totalCount = 10, neighbours = listOf(testNeighbour(), testNeighbour())),
            2 to testNeighboursResponse(totalCount = 10, neighbours = emptyList()),
        )
        val result = NeighboursPagination.collectingAllPages { offset -> pages.getValue(offset) }
        // Truncated: node claimed 10 but stopped returning rows, so the caller can detect the shortfall.
        assertEquals(2, result.neighbours.size)
        assertEquals(10, result.totalCount)
    }

    @Test
    fun singlePageAtOrAboveTotalStopsImmediately() = runTest {
        var calls = 0
        val result = NeighboursPagination.collectingAllPages { _ ->
            calls++
            testNeighboursResponse(totalCount = 1, neighbours = listOf(testNeighbour()))
        }
        assertEquals(1, calls)
        assertEquals(1, result.neighbours.size)
    }
}
