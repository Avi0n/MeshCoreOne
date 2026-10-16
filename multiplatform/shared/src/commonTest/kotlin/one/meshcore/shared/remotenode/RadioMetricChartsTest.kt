package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.battery.OCVPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Radio history series extraction for the telemetry overview. */
class RadioMetricChartsTest {
    private val nodeKey = testPublicKey()

    private data class MetricSeed(
        val battery: Int? = null,
        val snr: Double? = null,
        val rssi: Int? = null,
        val noise: Int? = null,
        val sentDirect: Long? = null,
        val sentFlood: Long? = null,
        val receivedDirect: Long? = null,
        val receivedFlood: Long? = null,
        val directDup: Long? = null,
        val floodDup: Long? = null,
        val errors: Long? = null,
        val posts: Int? = null,
        val pushes: Int? = null,
    )

    private fun snapshot(id: Int, timestamp: Double, seed: MetricSeed = MetricSeed()): NodeStatusSnapshotDto =
        NodeStatusSnapshotDto(
            id = testUuid(id),
            timestamp = timestamp,
            nodePublicKey = nodeKey,
            batteryMillivolts = seed.battery,
            lastSNR = seed.snr,
            lastRSSI = seed.rssi,
            noiseFloor = seed.noise,
            sentDirect = seed.sentDirect,
            sentFlood = seed.sentFlood,
            receivedDirect = seed.receivedDirect,
            receivedFlood = seed.receivedFlood,
            directDuplicates = seed.directDup,
            floodDuplicates = seed.floodDup,
            receiveErrors = seed.errors,
            postedCount = seed.posts,
            postPushCount = seed.pushes,
        )

    @Test
    fun singleSeriesOmitsEmptyMetricsAndConvertsBatteryToVolts() {
        val snapshots = listOf(
            snapshot(1, 100.0, MetricSeed(battery = 3700, snr = 5.5)),
            snapshot(2, 200.0, MetricSeed(battery = 3900, snr = 6.0)),
        )
        val series = RadioMetricCharts.singleSeries(snapshots)
        assertEquals(
            listOf(
                RadioMetricCharts.KEY_BATTERY,
                RadioMetricCharts.KEY_SNR,
            ),
            series.map { it.key },
        )
        assertEquals(listOf(3.7, 3.9), series.first { it.key == RadioMetricCharts.KEY_BATTERY }.points.map { it.value })
        assertEquals(RadioMetricCharts.UNIT_VOLTS, series.first { it.key == RadioMetricCharts.KEY_BATTERY }.unit)
        assertEquals(listOf(5.5, 6.0), series.first { it.key == RadioMetricCharts.KEY_SNR }.points.map { it.value })
    }

    @Test
    fun packetOverlaysKeepDirectAndFloodAndDropEmptyPairs() {
        val snapshots = listOf(
            snapshot(1, 100.0, MetricSeed(sentDirect = 10, sentFlood = 2, receivedDirect = 8)),
            snapshot(2, 200.0, MetricSeed(sentDirect = 12, sentFlood = 3, receivedDirect = 9)),
        )
        val overlays = RadioMetricCharts.packetOverlays(snapshots)
        assertEquals(
            listOf(RadioMetricCharts.KEY_PACKETS_SENT, RadioMetricCharts.KEY_PACKETS_RECEIVED),
            overlays.map { it.key },
        )
        val sent = overlays.first { it.key == RadioMetricCharts.KEY_PACKETS_SENT }
        assertEquals(listOf(10.0, 12.0), sent.direct.map { it.value })
        assertEquals(listOf(2.0, 3.0), sent.flood.map { it.value })
        val received = overlays.first { it.key == RadioMetricCharts.KEY_PACKETS_RECEIVED }
        assertEquals(listOf(8.0, 9.0), received.direct.map { it.value })
        assertTrue(received.flood.isEmpty())
    }

    @Test
    fun emptySnapshotsYieldNoCharts() {
        assertTrue(RadioMetricCharts.singleSeries(emptyList()).isEmpty())
        assertTrue(RadioMetricCharts.packetOverlays(emptyList()).isEmpty())
        assertTrue(RadioMetricCharts.singleSeries(listOf(snapshot(1, 1.0))).isEmpty())
    }

    @Test
    fun roomPostsAndReceiveErrorsAppearInSingleSeries() {
        val snapshots = listOf(
            snapshot(1, 100.0, MetricSeed(errors = 1, posts = 4, pushes = 2)),
            snapshot(2, 200.0, MetricSeed(errors = 2, posts = 5, pushes = 3)),
        )
        val keys = RadioMetricCharts.singleSeries(snapshots).map { it.key }
        assertEquals(
            listOf(
                RadioMetricCharts.KEY_RECEIVE_ERRORS,
                RadioMetricCharts.KEY_POSTS_RECEIVED,
                RadioMetricCharts.KEY_POSTS_PUSHED,
            ),
            keys,
        )
    }

    // MARK: - Y-axis domain anchoring (ported from iOS voltageChartDomain / sharedDomain)

    private val ocvArray = OCVPreset.LI_ION.ocvArray
    private val ocvMinVolts = ocvArray.min() / 1000.0
    private val ocvMaxVolts = ocvArray.max() / 1000.0
    private val bufferVolts = RadioMetricCharts.VOLTAGE_DOMAIN_BUFFER_MV / 1000.0

    private fun chartPoint(value: Double) = TelemetryChartPoint(testUuid(1), 0.0, value)

    @Test
    fun voltageChartDomainAnchorsToOcvCurveNotTightSampleCluster() {
        // A phone sitting near full charge only fluctuates within a few hundredths of a volt;
        // the domain must still span the whole curve so that tiny wobble isn't drawn as a dramatic swing.
        val nearFullSamples = listOf(chartPoint(4.10), chartPoint(4.12), chartPoint(4.15))
        val domain = RadioMetricCharts.voltageChartDomain(ocvArray, nearFullSamples)

        requireNotNull(domain)
        assertEquals(ocvMinVolts - bufferVolts, domain.start)
        assertEquals(ocvMaxVolts + bufferVolts, domain.endInclusive)

        val sampleSpan = nearFullSamples.maxOf { it.value } - nearFullSamples.minOf { it.value }
        val domainSpan = domain.endInclusive - domain.start
        assertTrue(domainSpan > sampleSpan * MIN_DOMAIN_TO_SAMPLE_SPAN_RATIO)
    }

    @Test
    fun voltageChartDomainKeepsLowBatteryDistinguishableFromCutoff() {
        // A reading just above the curve's real low-voltage cutoff should still land with headroom
        // above the axis floor, not get pinned to the bottom edge as if it were empty.
        val nearCutoffSample = listOf(chartPoint(ocvMinVolts + LOW_BATTERY_MARGIN_ABOVE_CUTOFF_VOLTS))
        val domain = RadioMetricCharts.voltageChartDomain(ocvArray, nearCutoffSample)

        requireNotNull(domain)
        val marginAboveFloor = nearCutoffSample.first().value - domain.start
        assertTrue(marginAboveFloor >= bufferVolts)
    }

    @Test
    fun voltageChartDomainExpandsToCoverAnOutlierSampleBelowTheCurve() {
        val outlier = listOf(chartPoint(ocvMinVolts - OUTLIER_BELOW_CURVE_VOLTS))
        val domain = RadioMetricCharts.voltageChartDomain(ocvArray, outlier)

        requireNotNull(domain)
        assertEquals(0.0.coerceAtLeast(ocvMinVolts - OUTLIER_BELOW_CURVE_VOLTS - bufferVolts), domain.start)
    }

    @Test
    fun sensorYAxisDomainAnchorsVoltageAndAutoscalesOthers() {
        val samples = listOf(chartPoint(4.10), chartPoint(4.12))
        val voltage = RadioMetricCharts.sensorYAxisDomain(LPPSensorType.VOLTAGE, samples, ocvArray)
        assertEquals(RadioMetricCharts.voltageChartDomain(ocvArray, samples), voltage)
        assertEquals(null, RadioMetricCharts.sensorYAxisDomain(LPPSensorType.TEMPERATURE, samples, ocvArray))
        assertEquals(null, RadioMetricCharts.sensorYAxisDomain(null, samples, ocvArray))
    }

    @Test
    fun voltageChartDomainIsNullForAnEmptyOcvArray() {
        assertNull(RadioMetricCharts.voltageChartDomain(emptyList()))
    }

    @Test
    fun sharedDomainIsZeroAnchoredWithHeadroomAboveTheHighestSample() {
        val direct = listOf(chartPoint(4.0), chartPoint(20.0))
        val flood = listOf(chartPoint(3.0))
        val domain = RadioMetricCharts.sharedDomain(listOf(direct, flood))

        requireNotNull(domain)
        assertEquals(0.0, domain.start)
        assertEquals(20.0 * SHARED_DOMAIN_HEADROOM_FOR_TEST, domain.endInclusive)
    }

    @Test
    fun sharedDomainIsNullWhenEveryArrayIsEmptyOrZero() {
        assertNull(RadioMetricCharts.sharedDomain(emptyList()))
        assertNull(RadioMetricCharts.sharedDomain(listOf(emptyList(), emptyList())))
        assertNull(RadioMetricCharts.sharedDomain(listOf(listOf(chartPoint(0.0)), listOf(chartPoint(0.0)))))
    }

    private companion object {
        const val MIN_DOMAIN_TO_SAMPLE_SPAN_RATIO = 10.0
        const val LOW_BATTERY_MARGIN_ABOVE_CUTOFF_VOLTS = 0.05
        const val OUTLIER_BELOW_CURVE_VOLTS = 0.6
        const val SHARED_DOMAIN_HEADROOM_FOR_TEST = 1.05
    }
}
