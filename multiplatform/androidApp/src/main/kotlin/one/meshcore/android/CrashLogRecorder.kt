package one.meshcore.android

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogBuffer
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.DebugLogSource
import one.meshcore.shared.settings.PersistentLogger
import kotlin.uuid.ExperimentalUuidApi

/**
 * Writes an uncaught JVM exception, and everything still buffered ahead of it, into the persistent debug
 * log before the process dies. The buffer's debounced flush otherwise loses the last few seconds of lines
 * before a crash, which is exactly the window a debug export needs. Native (Swift) faults kill the
 * process without passing through here.
 */
object CrashLogRecorder {
    private const val CATEGORY = "Crash"
    private const val MAX_TRACE_CHARS = 6_000
    private const val FLUSH_BUDGET_MS = 2_000L

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun record(thread: Thread, error: Throwable) {
        val entry = DebugLogEntryDto.of(
            id = Uuid.normalized(kotlin.uuid.Uuid.random().toString()),
            timestamp = System.currentTimeMillis(),
            level = DebugLogLevel.FAULT,
            source = DebugLogSource(PersistentLogger.DEFAULT_SUBSYSTEM, CATEGORY),
            message = "Uncaught on ${thread.name}: ${error.stackTraceToString().take(MAX_TRACE_CHARS)}",
        )
        runBlocking {
            withTimeoutOrNull(FLUSH_BUDGET_MS) {
                val buffer = DebugLogBuffer.getShared() ?: return@withTimeoutOrNull
                buffer.append(entry)
                buffer.flush()
            }
        }
    }
}
