package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey
import one.meshcore.shared.model.Uuid

/**
 * Persistence mirror of the SwiftData `TracePathRun` model. Per-hop SNR is stored as a `List<Double>`
 * via the double-list converter, the JSON-text equivalent of SwiftData's `hopsData` JSON blob.
 */
@Entity(tableName = "trace_path_run")
data class TracePathRunEntity(
    @PrimaryKey val id: Uuid,
    val savedPathID: Uuid,
    val date: Double,
    val success: Boolean,
    val roundTripMs: Int,
    val hopsSNR: List<Double> = emptyList(),
)
