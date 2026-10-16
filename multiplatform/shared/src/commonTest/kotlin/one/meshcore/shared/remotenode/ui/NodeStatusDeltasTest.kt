package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.testNeighbour
import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.remotenode.testStatusResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeStatusDeltasTest {
    private val nodeKey = testPublicKey()

    @Test
    fun fromStatusComputesVoltageAndRadioDeltas() {
        val previous = snapshot(
            battery = 3800,
            snr = 7.0,
            rssi = -90,
            noise = -118,
        )
        val current = testStatusResponse(
            publicKey = nodeKey,
            battery = 3900,
            lastSNR = 8.5,
            lastRSSI = -87,
            noiseFloor = -120,
        )
        val deltas = NodeStatusDeltas.fromStatus(current, previous)
        assertEquals(0.1, deltas.batteryVolts!!, absoluteTolerance = 1e-9)
        assertEquals(1.5, deltas.snr!!, absoluteTolerance = 1e-9)
        assertEquals(3.0, deltas.rssi!!, absoluteTolerance = 1e-9)
        assertEquals(-2.0, deltas.noiseFloor!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun fromStatusNullWhenNoBaseline() {
        val deltas = NodeStatusDeltas.fromStatus(testStatusResponse(publicKey = nodeKey), previous = null)
        assertNull(deltas.batteryVolts)
        assertNull(deltas.snr)
        assertNull(deltas.rssi)
        assertNull(deltas.noiseFloor)
    }

    @Test
    fun neighborSnrDeltaRespectsThreshold() {
        val prefix = ByteArray(6) { 0x42 }
        val neighbor = testNeighbour(prefix = 0x42, snr = 8.0)
        val previous = listOf(NeighborSnapshotEntry(publicKeyPrefix = prefix, snr = 7.95, secondsAgo = 30))
        assertNull(NodeStatusDeltas.neighborSnrDelta(neighbor, previous))

        val larger = listOf(NeighborSnapshotEntry(publicKeyPrefix = prefix, snr = 7.0, secondsAgo = 30))
        assertEquals(1.0, NodeStatusDeltas.neighborSnrDelta(neighbor, larger)!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun isNewNeighborRequiresPriorNeighborSnapshot() {
        val prefix = ByteArray(6) { 0x11 }
        assertFalse(
            NodeStatusDeltas.isNewNeighbor(
                prefix = prefix,
                previousNeighborSnapshot = null,
                seenPrefixes = emptySet(),
            ),
        )
        val prev = snapshot(neighbors = emptyList())
        assertTrue(
            NodeStatusDeltas.isNewNeighbor(
                prefix = prefix,
                previousNeighborSnapshot = prev.copy(
                    neighborSnapshots = listOf(
                        NeighborSnapshotEntry(publicKeyPrefix = ByteArray(6) { 0x22 }, snr = 1.0, secondsAgo = 1),
                    ),
                ),
                seenPrefixes = setOf(listOf(0x22, 0x22, 0x22, 0x22, 0x22, 0x22)),
            ),
        )
        assertFalse(
            NodeStatusDeltas.isNewNeighbor(
                prefix = prefix,
                previousNeighborSnapshot = prev.copy(
                    neighborSnapshots = listOf(
                        NeighborSnapshotEntry(publicKeyPrefix = prefix, snr = 1.0, secondsAgo = 1),
                    ),
                ),
                seenPrefixes = setOf(prefix.toList()),
            ),
        )
    }

    @Test
    fun disappearedNeighborsFiltersCurrentPrefixes() {
        val kept = ByteArray(6) { 0x01 }
        val gone = ByteArray(6) { 0x02 }
        val previous = listOf(
            NeighborSnapshotEntry(publicKeyPrefix = kept, snr = 5.0, secondsAgo = 10),
            NeighborSnapshotEntry(publicKeyPrefix = gone, snr = 3.0, secondsAgo = 20),
        )
        val current = listOf(testNeighbour(prefix = 0x01, snr = 5.5))
        val disappeared = NodeStatusDeltas.disappearedNeighbors(current, previous)
        assertEquals(1, disappeared.size)
        assertTrue(disappeared[0].publicKeyPrefix.contentEquals(gone))
    }

    private fun snapshot(
        battery: Int? = null,
        snr: Double? = null,
        rssi: Int? = null,
        noise: Int? = null,
        neighbors: List<NeighborSnapshotEntry>? = null,
    ): NodeStatusSnapshotDto = NodeStatusSnapshotDto(
        id = Uuid.normalized("S0000000-0000-0000-0000-000000000001"),
        timestamp = 1_000.0,
        nodePublicKey = nodeKey,
        batteryMillivolts = battery,
        lastSNR = snr,
        lastRSSI = rssi,
        noiseFloor = noise,
        neighborSnapshots = neighbors,
    )
}
