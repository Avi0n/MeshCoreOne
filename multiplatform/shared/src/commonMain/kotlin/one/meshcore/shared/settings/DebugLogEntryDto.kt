package one.meshcore.shared.settings

import one.meshcore.shared.model.Uuid

/** Bytes of a message kept in a persisted entry, mirroring the Swift `DebugLogEntryDTO` 4000-char cap. */
private const val MESSAGE_MAX_LENGTH = 4000

/** Subsystem and category stamped on one persisted debug-log row. */
data class DebugLogSource(val subsystem: String, val category: String)

/**
 * A debug log entry for cross-layer transfer and persistence, mirroring the Swift `DebugLogEntryDTO`. The
 * message is truncated to [MESSAGE_MAX_LENGTH] characters on construction so a runaway log line cannot bloat
 * memory or the store. [timestamp] is epoch milliseconds.
 */
data class DebugLogEntryDto(
    val id: Uuid,
    val timestamp: Long,
    val level: DebugLogLevel,
    val subsystem: String,
    val category: String,
    val message: String,
) {
    companion object {
        /** Builds an entry, truncating [message] to the persisted cap (the id/timestamp come from the caller). */
        fun of(
            id: Uuid,
            timestamp: Long,
            level: DebugLogLevel,
            source: DebugLogSource,
            message: String,
        ): DebugLogEntryDto = DebugLogEntryDto(
            id = id,
            timestamp = timestamp,
            level = level,
            subsystem = source.subsystem,
            category = source.category,
            message = message.take(MESSAGE_MAX_LENGTH),
        )
    }
}
