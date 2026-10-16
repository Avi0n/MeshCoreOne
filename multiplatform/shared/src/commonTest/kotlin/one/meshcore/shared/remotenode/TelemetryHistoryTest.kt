package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.backup.TelemetrySnapshotEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** History time-range windowing, channel-group aggregation, and overview presence derivation. */
class TelemetryHistoryTest {
    private val nodeKey = testPublicKey()
    private val now = 1_000_000.0
    private val day = 86_400.0

    private fun snapshot(
        id: Int,
        timestamp: Double,
        battery: Int? = null,
        telemetry: List<TelemetrySnapshotEntry>? = null,
        neighbors: List<NeighborSnapshotEntry>? = null,
    ): NodeStatusSnapshotDto = NodeStatusSnapshotDto(
        id = testUuid(id),
        timestamp = timestamp,
        nodePublicKey = nodeKey,
        batteryMillivolts = battery,
        telemetryEntries = telemetry,
        neighborSnapshots = neighbors,
    )

    @Test
    fun timeRangeStartOffsets() {
        assertEquals(now - 7 * day, HistoryTimeRange.WEEK.startEpochSeconds(now))
        assertEquals(now - 30 * day, HistoryTimeRange.MONTH.startEpochSeconds(now))
        assertEquals(now - 90 * day, HistoryTimeRange.THREE_MONTHS.startEpochSeconds(now))
        assertNull(HistoryTimeRange.ALL.startEpochSeconds(now))
        assertEquals(HistoryTimeRange.MONTH, HistoryTimeRange.DEFAULT)
    }

    @Test
    fun timeRangeFilterKeepsOnlyInWindow() {
        val snapshots = listOf(
            snapshot(1, now - 100 * day),
            snapshot(2, now - 20 * day),
            snapshot(3, now - 2 * day),
        )
        assertEquals(listOf(testUuid(3)), HistoryTimeRange.WEEK.filter(snapshots, now).map { it.id })
        assertEquals(
            listOf(testUuid(2), testUuid(3)),
            HistoryTimeRange.MONTH.filter(snapshots, now).map { it.id },
        )
        assertEquals(3, HistoryTimeRange.ALL.filter(snapshots, now).size)
    }

    @Test
    fun channelGroupsSortChannelsAndPutVoltageFirst() {
        val snapshots = listOf(
            snapshot(
                1,
                now,
                telemetry = listOf(
                    TelemetrySnapshotEntry(channel = 2, type = "Humidity", value = 55.0),
                    TelemetrySnapshotEntry(channel = 1, type = "Temperature", value = 21.0),
                    TelemetrySnapshotEntry(channel = 1, type = "Voltage", value = 4.0),
                ),
            ),
        )
        val groups = ChannelGroup.groups(snapshots)
        assertEquals(listOf(1, 2), groups.map { it.channel })
        // Within channel 1, voltage (priority 0) precedes temperature (priority 1).
        assertEquals(listOf("Voltage", "Temperature"), groups[0].charts.map { it.title })
    }

    @Test
    fun channelGroupsConvertValuesForImperial() {
        val snapshots = listOf(
            snapshot(
                1,
                now,
                telemetry = listOf(TelemetrySnapshotEntry(channel = 1, type = "Temperature", value = 25.0)),
            ),
        )
        val metric = ChannelGroup.groups(snapshots, MeasurementSystem.METRIC)
        assertEquals(25.0, metric[0].charts[0].points[0].value)

        val imperial = ChannelGroup.groups(snapshots, MeasurementSystem.IMPERIAL)
        assertEquals(77.0, imperial[0].charts[0].points[0].value, 0.0001)
    }

    @Test
    fun secondTemperaturePerSnapshotUsesSeparateMcuSeries() {
        val snapshots = listOf(1, 2).map { id ->
            snapshot(
                id,
                now + id,
                telemetry = listOf(
                    TelemetrySnapshotEntry(1, "Temperature", 20.0 + id),
                    TelemetrySnapshotEntry(1, "Temperature", 30.0 + id),
                ),
            )
        }
        val charts = ChannelGroup.groups(snapshots).single().charts.associateBy { it.key }
        assertEquals(listOf(21.0, 22.0), charts.getValue("1-Temperature").points.map { it.value })
        assertEquals(listOf(31.0, 32.0), charts.getValue("1-Temperature-mcu").points.map { it.value })
        assertEquals(LPPSensorType.TEMPERATURE, charts.getValue("1-Temperature-mcu").sensorType)
    }

    @Test
    fun overviewPresenceChecks() {
        val radio = listOf(snapshot(1, now, battery = 3900))
        val telemetry = listOf(
            snapshot(2, now, telemetry = listOf(TelemetrySnapshotEntry(1, "Voltage", 4.0))),
        )
        val neighbors = listOf(
            snapshot(3, now, neighbors = listOf(NeighborSnapshotEntry(testPublicKey(1).copyOf(6), 5.0, 10))),
        )
        val empty = listOf(snapshot(4, now))

        assertTrue(TelemetryOverview.hasRadioData(radio))
        assertTrue(TelemetryOverview.hasTelemetryData(telemetry))
        assertTrue(TelemetryOverview.hasNeighborData(neighbors))
        assertFalse(TelemetryOverview.hasRadioData(empty))
        assertFalse(TelemetryOverview.hasTelemetryData(empty))
        assertFalse(TelemetryOverview.hasNeighborData(empty))
    }

    @Test
    fun chatSavedHistoryHidesNeighborsEvenWhenSnapshotsCarrySnr() {
        assertFalse(TelemetryOverview.showsNeighborCharts(showNeighbors = false, hasNeighborData = true))
        assertFalse(TelemetryOverview.showsNeighborsNotCaptured(showNeighbors = false, hasNeighborData = false))
        assertTrue(TelemetryOverview.showsNeighborCharts(showNeighbors = true, hasNeighborData = true))
        assertTrue(TelemetryOverview.showsNeighborsNotCaptured(showNeighbors = true, hasNeighborData = false))
    }

    @Test
    fun emptyTelemetryProducesNoChannelGroups() {
        assertTrue(ChannelGroup.groups(listOf(snapshot(1, now))).isEmpty())
        assertTrue(ChannelGroup.groups(emptyList<NodeStatusSnapshotDto>()).isEmpty())
    }

    @Test
    fun unknownSensorNameFallsBackToRawValueAndType() {
        val snapshots = listOf(
            snapshot(1, now, telemetry = listOf(TelemetrySnapshotEntry(1, "Mystery", 7.0))),
        )
        val groups = ChannelGroup.groups(snapshots, MeasurementSystem.IMPERIAL)
        val chart = groups[0].charts[0]
        assertNull(chart.sensorType)
        assertEquals("Mystery", chart.title)
        // No conversion is applied for an unrecognized type, even on imperial.
        assertEquals(7.0, chart.points[0].value)
    }
}
