package one.meshcore.shared.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Batch aggregates read only successful runs, matching the Swift `successfulResults`-based aggregates. */
class TraceBatchAggregationTest {
    private fun startHop() =
        TraceHop(hashBytes = null, resolvedName = "Me", snr = 0.0, isStartNode = true, isEndNode = false)

    private fun intermediate(snr: Double) =
        TraceHop(hashBytes = byteArrayOf(0x31), resolvedName = "R", snr = snr, isStartNode = false, isEndNode = false)

    private fun result(durationMs: Int, success: Boolean, hopSnrs: List<Double>): TraceResult {
        val hops = listOf(startHop()) + hopSnrs.map { intermediate(it) }
        return TraceResult(
            hops,
            durationMs,
            success,
            errorMessage = null,
            tracedPathBytes = byteArrayOf(0x31),
            hashSize = 1,
        )
    }

    @Test
    fun rttAggregatesIgnoreFailedRuns() {
        val results = listOf(
            result(100, success = true, hopSnrs = listOf(5.0)),
            result(0, success = false, hopSnrs = emptyList()),
            result(300, success = true, hopSnrs = listOf(3.0)),
        )
        assertEquals(200, averageRtt(results)) // (100 + 300) / 2
        assertEquals(100, minRtt(results))
        assertEquals(300, maxRtt(results))
    }

    @Test
    fun rttAggregatesAreNullWhenNoRunSucceeds() {
        val results = listOf(result(0, success = false, hopSnrs = emptyList()))
        assertNull(averageRtt(results))
        assertNull(minRtt(results))
        assertNull(maxRtt(results))
    }

    @Test
    fun hopStatsIsNullForStartNodeIndex() {
        val results = listOf(result(100, success = true, hopSnrs = listOf(5.0)))
        assertNull(hopStats(results, index = 0))
    }

    @Test
    fun hopStatsAggregatesReceivedSnrAcrossSuccesses() {
        val results = listOf(
            result(100, success = true, hopSnrs = listOf(2.0)),
            result(0, success = false, hopSnrs = listOf(99.0)), // ignored
            result(200, success = true, hopSnrs = listOf(8.0)),
        )
        val stats = hopStats(results, index = 1)
        assertEquals(HopSnrStats(avg = 5.0, min = 2.0, max = 8.0), stats)
    }

    @Test
    fun latestHopSnrReadsMostRecentSuccess() {
        val results = listOf(
            result(100, success = true, hopSnrs = listOf(2.0)),
            result(200, success = true, hopSnrs = listOf(7.0)),
            result(0, success = false, hopSnrs = emptyList()),
        )
        assertEquals(7.0, latestHopSnr(results, index = 1))
        assertNull(latestHopSnr(results, index = 5))
    }

    @Test
    fun batchSuccessPercentUsesIntegerDivisionLikeIos() {
        assertEquals(0, batchSuccessPercent(successCount = 0, batchSize = 0))
        assertEquals(0, batchSuccessPercent(successCount = 0, batchSize = 3))
        assertEquals(66, batchSuccessPercent(successCount = 2, batchSize = 3))
        assertEquals(100, batchSuccessPercent(successCount = 3, batchSize = 3))
    }
}
