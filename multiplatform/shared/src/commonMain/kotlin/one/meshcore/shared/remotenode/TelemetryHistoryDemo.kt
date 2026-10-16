package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.backup.TelemetrySnapshotEntry
import one.meshcore.shared.model.Uuid

/**
 * Fixture snapshots for offline / demo review of the telemetry history charts when no live
 * node status has been captured yet. Deterministic values so screenshots and unit tests match.
 */
object TelemetryHistoryDemo {
    private const val SAMPLE_COUNT = 8
    private const val HOUR_SECONDS = 3_600.0
    private const val NEIGHBOR_PREFIX_LENGTH = 6
    private const val UUID_PAD_WIDTH = 12
    private const val BASE_BATTERY_MV = 3_700
    private const val BATTERY_STEP_MV = 25
    private const val BASE_SNR = 4.0
    private const val SNR_STEP = 0.4
    private const val NEIGHBOR_A_SNR_STEP = 0.3
    private const val NEIGHBOR_B_SNR_STEP = 0.2
    private const val NEIGHBOR_B_SNR_OFFSET = 1.0
    private const val BASE_RSSI = -110
    private const val RSSI_STEP = 2
    private const val BASE_NOISE = -120
    private const val BASE_TEMP_C = 18.0
    private const val TEMP_STEP = 0.5
    private const val BASE_HUMIDITY = 45.0
    private const val HUMIDITY_STEP = 1.5
    private const val BASE_VOLTAGE = 3.7
    private const val VOLTAGE_STEP = 0.02
    private const val PREFIX_A: Byte = 0xAA.toByte()
    private const val PREFIX_B: Byte = 0xBB.toByte()
    private const val DEMO_UUID_PREFIX = "B0000000-0000-0000-0000-"
    private const val BASE_SENT_DIRECT = 10L
    private const val SENT_DIRECT_STEP = 3L
    private const val BASE_RECEIVED_DIRECT = 8L
    private const val RECEIVE_ERROR_PERIOD = 3
    private const val NEIGHBOR_A_SECONDS_AGO = 10
    private const val NEIGHBOR_B_SECONDS_AGO = 20

    /**
     * [count] synthetic status snapshots ending at [nowEpochSeconds], spaced one hour apart.
     * Includes radio metrics, one sensor channel, and two neighbour SNR series.
     */
    fun sampleSnapshots(
        nodePublicKey: ByteArray,
        nowEpochSeconds: Double,
        count: Int = SAMPLE_COUNT,
    ): List<NodeStatusSnapshotDto> {
        require(count > 0)
        val neighborA = ByteArray(NEIGHBOR_PREFIX_LENGTH) { PREFIX_A }
        val neighborB = ByteArray(NEIGHBOR_PREFIX_LENGTH) { PREFIX_B }
        return (0 until count).map { index ->
            val age = (count - 1 - index).toDouble() * HOUR_SECONDS
            val timestamp = nowEpochSeconds - age
            val idSuffix = (index + 1).toString().padStart(UUID_PAD_WIDTH, '0')
            NodeStatusSnapshotDto(
                id = Uuid.normalized("$DEMO_UUID_PREFIX$idSuffix"),
                timestamp = timestamp,
                nodePublicKey = nodePublicKey,
                batteryMillivolts = BASE_BATTERY_MV + index * BATTERY_STEP_MV,
                lastSNR = BASE_SNR + index * SNR_STEP,
                lastRSSI = BASE_RSSI + index * RSSI_STEP,
                noiseFloor = BASE_NOISE + index,
                sentDirect = BASE_SENT_DIRECT + index * SENT_DIRECT_STEP,
                sentFlood = (2L + index),
                receivedDirect = BASE_RECEIVED_DIRECT + index * 2,
                receivedFlood = (1L + index),
                directDuplicates = index.toLong(),
                floodDuplicates = (index / 2).toLong(),
                receiveErrors = if (index % RECEIVE_ERROR_PERIOD == 0) 1L else 0L,
                postedCount = index,
                postPushCount = index / 2,
                neighborSnapshots = listOf(
                    NeighborSnapshotEntry(
                        neighborA,
                        snr = BASE_SNR + index * NEIGHBOR_A_SNR_STEP,
                        secondsAgo = NEIGHBOR_A_SECONDS_AGO,
                    ),
                    NeighborSnapshotEntry(
                        neighborB,
                        snr = BASE_SNR - NEIGHBOR_B_SNR_OFFSET + index * NEIGHBOR_B_SNR_STEP,
                        secondsAgo = NEIGHBOR_B_SECONDS_AGO,
                    ),
                ),
                telemetryEntries = listOf(
                    TelemetrySnapshotEntry(
                        channel = 1,
                        type = "Temperature",
                        value = BASE_TEMP_C + index * TEMP_STEP,
                    ),
                    TelemetrySnapshotEntry(
                        channel = 1,
                        type = "Humidity",
                        value = BASE_HUMIDITY + index * HUMIDITY_STEP,
                    ),
                    TelemetrySnapshotEntry(
                        channel = 1,
                        type = "Voltage",
                        value = BASE_VOLTAGE + index * VOLTAGE_STEP,
                    ),
                ),
            )
        }
    }
}
