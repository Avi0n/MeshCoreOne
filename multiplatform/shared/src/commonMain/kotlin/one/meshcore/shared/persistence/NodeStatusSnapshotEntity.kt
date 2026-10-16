package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey
import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.TelemetrySnapshotEntry
import one.meshcore.shared.model.Uuid

/**
 * Persistence mirror of the SwiftData `NodeStatusSnapshot` model, keyed to its node by
 * [nodePublicKey]. Optional neighbor and telemetry lists persist as JSON text via their converters.
 */
@Entity(tableName = "node_status_snapshot")
data class NodeStatusSnapshotEntity(
    @PrimaryKey val id: Uuid,
    val timestamp: Double,
    val nodePublicKey: ByteArray,
    val batteryMillivolts: Int? = null,
    val lastSNR: Double? = null,
    val lastRSSI: Int? = null,
    val noiseFloor: Int? = null,
    val uptimeSeconds: Long? = null,
    val rxAirtimeSeconds: Long? = null,
    val packetsSent: Long? = null,
    val packetsReceived: Long? = null,
    val receiveErrors: Long? = null,
    val sentDirect: Long? = null,
    val sentFlood: Long? = null,
    val receivedDirect: Long? = null,
    val receivedFlood: Long? = null,
    val directDuplicates: Long? = null,
    val floodDuplicates: Long? = null,
    val postedCount: Int? = null,
    val postPushCount: Int? = null,
    val neighborSnapshots: List<NeighborSnapshotEntry>? = null,
    val telemetryEntries: List<TelemetrySnapshotEntry>? = null,
    // Primary GPS fix at capture time. Latitude and longitude are both set or both null; dropped when
    // the node had no lock. Written first-wins during in-window enrichment so a later no-fix response
    // can't erase a good fix. Altitude is optional even when a coordinate is present.
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
)
