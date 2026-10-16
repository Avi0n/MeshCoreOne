package one.meshcore.shared.trace

import one.meshcore.shared.map.LineStyle
import one.meshcore.shared.map.PinStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TracePathMapBuilderTest {
    @Test
    fun failedResultYieldsEmptyPlot() {
        val plot = TracePathMapBuilder.buildFromResult(
            TraceResult.failure("timeout", attemptedPath = byteArrayOf(1), hashSize = 1),
        )
        assertTrue(plot.points.isEmpty())
        assertTrue(plot.lines.isEmpty())
    }

    @Test
    fun locatedHopsProduceSnrColoredSegmentsAndBadges() {
        val result = TraceResult(
            hops = listOf(
                hop(isStart = true, lat = 37.0, lon = -122.0, snr = 0.0),
                hop(isStart = false, lat = 37.1, lon = -122.1, snr = 8.0),
                hop(isStart = false, lat = 37.2, lon = -122.2, snr = -8.0, isEnd = true),
            ),
            durationMs = 120,
            success = true,
            errorMessage = null,
            tracedPathBytes = byteArrayOf(0xAB.toByte(), 0xCD.toByte()),
            hashSize = 1,
        )

        val plot = TracePathMapBuilder.buildFromResult(result)

        assertEquals(3, plot.points.count { it.pinStyle != PinStyle.BADGE })
        assertEquals(2, plot.lines.size)
        assertEquals(LineStyle.TRACE_GOOD, plot.lines[0].style)
        assertEquals(LineStyle.TRACE_WEAK, plot.lines[1].style)
        assertEquals(2, plot.points.count { it.pinStyle == PinStyle.BADGE })
        assertTrue(plot.points.none { it.isClusterable })
    }

    @Test
    fun hopsWithoutLocationAreSkipped() {
        val result = TraceResult(
            hops = listOf(
                hop(isStart = true, lat = 37.0, lon = -122.0, snr = 0.0),
                hop(isStart = false, lat = null, lon = null, snr = 5.0),
                hop(isStart = false, lat = 37.2, lon = -122.2, snr = 4.0, isEnd = true),
            ),
            durationMs = 50,
            success = true,
            errorMessage = null,
            tracedPathBytes = byteArrayOf(1),
            hashSize = 1,
        )

        val plot = TracePathMapBuilder.buildFromResult(result)
        assertEquals(2, plot.points.count { it.pinStyle != PinStyle.BADGE })
        assertEquals(1, plot.lines.size)
    }

    private fun hop(isStart: Boolean, lat: Double?, lon: Double?, snr: Double, isEnd: Boolean = false): TraceHop =
        TraceHop(
            hashBytes = if (isStart) null else byteArrayOf(0x11),
            resolvedName = if (isStart) "Me" else "R1",
            snr = snr,
            isStartNode = isStart,
            isEndNode = isEnd,
            latitude = lat,
            longitude = lon,
        )
}
