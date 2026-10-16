package one.meshcore.shared.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.model.Uuid
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Process-wide debug-log writer, the Kotlin analogue of iOS `PersistentLogger`. Info and above
 * record through [DebugLogBuffer] so a radio-drop export contains the connect timeline. Debug stays
 * off disk, matching iOS OSLog-only `.debug`.
 */
@OptIn(ExperimentalTime::class)
class PersistentLogger(
    private val scope: CoroutineScope,
    private val category: String,
    private val subsystem: String = DEFAULT_SUBSYSTEM,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newId: () -> Uuid = Uuid::random,
    private val record: suspend (DebugLogEntryDto) -> Unit = { DebugLogBuffer.record(it) },
) {
    @Suppress("UNUSED_PARAMETER")
    fun debug(message: String) {
        // Intentionally empty: debug is not persisted.
    }

    fun info(message: String) = persist(DebugLogLevel.INFO, message)

    fun notice(message: String) = persist(DebugLogLevel.NOTICE, message)

    fun warning(message: String) = persist(DebugLogLevel.WARNING, message)

    fun error(message: String) = persist(DebugLogLevel.ERROR, message)

    fun fault(message: String) = persist(DebugLogLevel.FAULT, message)

    private fun persist(level: DebugLogLevel, message: String) {
        val entry = DebugLogEntryDto.of(
            id = newId(),
            timestamp = now(),
            level = level,
            source = DebugLogSource(subsystem, category),
            message = message,
        )
        scope.launch { record(entry) }
    }

    companion object {
        const val DEFAULT_SUBSYSTEM: String = "com.mc1"
    }
}
