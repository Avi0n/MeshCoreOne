package one.meshcore.shared.persistence

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.TelemetrySnapshotEntry
import one.meshcore.shared.backup.backupJson

/** Room converters for neighbor and telemetry snapshot columns, using the backup list serializers. */
class SnapshotListConverters {
    @TypeConverter
    fun neighborsToString(value: List<NeighborSnapshotEntry>?): String? =
        value?.let { backupJson.encodeToString(ListSerializer(NeighborSnapshotEntry.serializer()), it) }

    @TypeConverter
    fun stringToNeighbors(value: String?): List<NeighborSnapshotEntry>? =
        value?.let { backupJson.decodeFromString(ListSerializer(NeighborSnapshotEntry.serializer()), it) }

    @TypeConverter
    fun telemetryToString(value: List<TelemetrySnapshotEntry>?): String? =
        value?.let { backupJson.encodeToString(ListSerializer(TelemetrySnapshotEntry.serializer()), it) }

    @TypeConverter
    fun stringToTelemetry(value: String?): List<TelemetrySnapshotEntry>? =
        value?.let { backupJson.decodeFromString(ListSerializer(TelemetrySnapshotEntry.serializer()), it) }
}
