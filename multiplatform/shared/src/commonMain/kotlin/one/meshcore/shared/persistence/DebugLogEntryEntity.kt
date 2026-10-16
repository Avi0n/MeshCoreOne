package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel

/**
 * Room row for diagnostics log lines, mirroring SwiftData `DebugLogEntry`. Indexed on [timestamp] for
 * newest-first export windows and oldest-first prune. Not part of the backup envelope (regenerable).
 */
@Entity(
    tableName = "debug_log_entry",
    indices = [Index(value = ["timestamp"])],
)
data class DebugLogEntryEntity(
    @PrimaryKey val id: Uuid,
    val timestamp: Long,
    val level: Int,
    val subsystem: String,
    val category: String,
    val message: String,
)

fun DebugLogEntryEntity.toDto(): DebugLogEntryDto = DebugLogEntryDto(
    id = id,
    timestamp = timestamp,
    level = DebugLogLevel.fromRaw(level),
    subsystem = subsystem,
    category = category,
    message = message,
)

fun DebugLogEntryDto.toEntity(): DebugLogEntryEntity = DebugLogEntryEntity(
    id = id,
    timestamp = timestamp,
    level = level.raw,
    subsystem = subsystem,
    category = category,
    message = message,
)
