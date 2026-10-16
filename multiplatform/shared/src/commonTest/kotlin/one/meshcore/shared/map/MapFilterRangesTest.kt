package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapFilterRangesTest {
    @Test
    fun lastHeardBoundariesAreInclusiveAndUnknownOrFutureStampsFollowSwift() {
        val range = MapLastHeardRange(MapDuration(1, MapDurationUnit.MINUTES), MapDuration(2, MapDurationUnit.MINUTES))
        assertTrue(range.contains(940L, 1_000L))
        assertTrue(range.contains(880L, 1_000L))
        assertFalse(range.contains(941L, 1_000L))
        assertFalse(range.contains(879L, 1_000L))
        assertFalse(range.contains(null, 1_000L))
        assertFalse(range.contains(1_001L, 1_000L))
        assertTrue(MapLastHeardRange.within(MapDuration(1, MapDurationUnit.MINUTES)).contains(1_001L, 1_000L))
        assertTrue(MapLastHeardRange.ANY.contains(null, 1_000L))
    }

    @Test
    fun crossingAgeBoundsMovesTheOppositeBoundAndPreservesUnits() {
        val hour = MapDuration(1, MapDurationUnit.HOURS)
        val day = MapDuration(1, MapDurationUnit.DAYS)
        val range = MapLastHeardRange(hour, day)
        val raised = range.withMinAge(MapDuration(2, MapDurationUnit.DAYS))
        assertEquals(raised.minAge, raised.maxAge)
        val lowered = range.withMaxAge(MapDuration(15, MapDurationUnit.MINUTES))
        assertEquals(lowered.maxAge, lowered.minAge)
        assertEquals(MapDurationUnit.MINUTES, lowered.minAge?.unit)
        assertEquals(day, range.withMinAge(null).maxAge)
        assertEquals(hour, range.withMaxAge(null).minAge)
        assertEquals(MapDuration(1, MapDurationUnit.HOURS), MapDuration(0, MapDurationUnit.HOURS))
        assertEquals(MapDuration(9_999, MapDurationUnit.DAYS), MapDuration(Int.MAX_VALUE, MapDurationUnit.DAYS))
    }

    @Test
    fun hopsNormalizeBoundsCeilingAndUnknownValues() {
        val range = MapHopRange(min = 1, max = 3)
        assertFalse(range.contains(0))
        assertTrue(range.contains(1))
        assertTrue(range.contains(3))
        assertFalse(range.contains(4))
        assertFalse(range.contains(null))
        assertTrue(MapHopRange.ANY.contains(null))
        assertTrue(MapHopRange.DIRECT.contains(0))
        assertFalse(MapHopRange.DIRECT.contains(null))
        assertEquals(MapHopRange(5, 5), range.withMin(5))
        assertEquals(MapHopRange(0, 0), range.withMax(0))
        assertEquals(MapHopRange(63), MapHopRange(Int.MAX_VALUE, -1))
        assertEquals(MapHopRange.ANY, MapHopRange(-1, 63))
        assertFalse(MapHopRange(0, 63).isActive)
    }

    @Test
    fun favoritesStillIntersectBothAdvancedDimensionsAndUnsupportedHostsIgnoreThem() {
        val state = MapFilterState(
            favoritesOnly = true,
            showChat = false,
            lastHeard = MapLastHeardRange.within(MapDuration(1, MapDurationUnit.HOURS)),
            hops = MapHopRange.DIRECT,
        )
        assertEquals(MapAdvancedFilterDimension.entries, state.activeAdvancedDimensions(MapFilterHost.MAIN_MAP))
        assertFalse(state.allowsAdvanced(900L, 2, 1_000L))
        assertFalse(state.allowsAdvanced(null, 0, 1_000L))
        assertTrue(state.allowsAdvanced(900L, 0, 1_000L))
        for (host in listOf(MapFilterHost.TRACE_PATH, MapFilterHost.NEIGHBOR_SNR)) {
            val sanitized = state.sanitized(host)
            assertFalse(sanitized.lastHeard.isActive)
            assertFalse(sanitized.hops.isActive)
            assertTrue(sanitized.activeAdvancedDimensions(host).isEmpty())
            assertTrue(MapFilterState.seed(host).copy(showChat = false, hops = MapHopRange.DIRECT).seedEquals(host))
        }
        assertFalse(state.clear(MapAdvancedFilterDimension.HOPS).hops.isActive)
        assertTrue(state.clear(MapAdvancedFilterDimension.HOPS).lastHeard.isActive)
        assertTrue(state.resetAdvanced().activeAdvancedDimensions(MapFilterHost.MAIN_MAP).isEmpty())
    }
}
