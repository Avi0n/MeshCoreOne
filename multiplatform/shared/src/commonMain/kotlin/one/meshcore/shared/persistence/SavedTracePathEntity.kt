package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey
import one.meshcore.shared.model.Uuid

/**
 * Persistence mirror of the SwiftData `SavedTracePath` model. Its runs are a separate entity
 * ([TracePathRunEntity]) linked by [TracePathRunEntity.savedPathID], mirroring the SwiftData
 * cascade relationship.
 */
@Entity(tableName = "saved_trace_path")
data class SavedTracePathEntity(
    @PrimaryKey val id: Uuid,
    val radioID: Uuid,
    val name: String,
    val pathBytes: ByteArray,
    val hashSize: Int = 1,
    val createdDate: Double,
)
