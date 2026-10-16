package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.model.toUppercaseHex
import kotlin.test.Test
import kotlin.test.assertEquals

/** Single-neighbour SNR history series shaping from snapshots. */
class NeighborChartTest {
    private val nodeKey = testPublicKey()
    private val target = ByteArray(6) { 0xAA.toByte() }
    private val other = ByteArray(6) { 0xBB.toByte() }

    private fun snapshot(id: Int, timestamp: Double, neighbors: List<NeighborSnapshotEntry>?) = NodeStatusSnapshotDto(
        id = testUuid(id),
        timestamp = timestamp,
        nodePublicKey = nodeKey,
        neighborSnapshots = neighbors,
    )

    @Test
    fun buildsPointsOnlyForMatchingNeighborPrefix() {
        val snapshots = listOf(
            snapshot(1, 100.0, listOf(NeighborSnapshotEntry(target, snr = 5.0, secondsAgo = 10))),
            snapshot(2, 200.0, listOf(NeighborSnapshotEntry(other, snr = 9.0, secondsAgo = 10))),
            snapshot(3, 300.0, listOf(NeighborSnapshotEntry(target, snr = 7.5, secondsAgo = 10))),
            snapshot(4, 400.0, neighbors = null),
        )
        val series = neighborSnrSeries(snapshots, target)
        assertEquals(2, series.size)
        assertEquals(listOf(100.0, 300.0), series.map { it.timestampSeconds })
        assertEquals(listOf(5.0, 7.5), series.map { it.value })
        assertEquals(testUuid(1), series[0].id)
    }

    @Test
    fun emptyWhenNoSnapshotCarriesTheNeighbor() {
        val snapshots = listOf(snapshot(1, 100.0, listOf(NeighborSnapshotEntry(other, 9.0, 10))))
        assertEquals(emptyList(), neighborSnrSeries(snapshots, target))
    }

    @Test
    fun neighborSnrChartsAggregatesAndSortsByResolvedName() {
        val snapshots = listOf(
            snapshot(
                1,
                100.0,
                listOf(
                    NeighborSnapshotEntry(target, snr = 5.0, secondsAgo = 10),
                    NeighborSnapshotEntry(other, snr = 1.0, secondsAgo = 10),
                ),
            ),
            snapshot(2, 200.0, listOf(NeighborSnapshotEntry(target, snr = 7.0, secondsAgo = 10))),
        )
        val charts = neighborSnrCharts(snapshots) { prefix ->
            when {
                prefix.contentEquals(target) -> "Zulu"
                prefix.contentEquals(other) -> "Alpha"
                else -> null
            }
        }
        assertEquals(listOf("Alpha", "Zulu"), charts.map { it.name })
        assertEquals(listOf(5.0, 7.0), charts.first { it.name == "Zulu" }.points.map { it.value })
        assertEquals(listOf(1.0), charts.first { it.name == "Alpha" }.points.map { it.value })
    }

    @Test
    fun neighborSnrChartsFallsBackToHexName() {
        val snapshots = listOf(snapshot(1, 100.0, listOf(NeighborSnapshotEntry(target, 4.0, 1))))
        val charts = neighborSnrCharts(snapshots)
        assertEquals(1, charts.size)
        assertEquals(target.toUppercaseHex(), charts[0].name)
    }

    @Test
    fun timeRangeFilterDropsOlderNeighborReadings() {
        val day = 86_400.0
        val now = 100 * day
        val snapshots = listOf(
            snapshot(1, now - 40 * day, listOf(NeighborSnapshotEntry(target, snr = 5.0, secondsAgo = 10))),
            snapshot(2, now - 2 * day, listOf(NeighborSnapshotEntry(target, snr = 7.0, secondsAgo = 10))),
        )
        val week = neighborSnrSeries(HistoryTimeRange.WEEK.filter(snapshots, now), target)
        assertEquals(listOf(7.0), week.map { it.value })
        val all = neighborSnrSeries(HistoryTimeRange.ALL.filter(snapshots, now), target)
        assertEquals(listOf(5.0, 7.0), all.map { it.value })
        val month = neighborSnrSeries(HistoryTimeRange.MONTH.filter(snapshots, now), target)
        assertEquals(listOf(7.0), month.map { it.value })
    }
}
