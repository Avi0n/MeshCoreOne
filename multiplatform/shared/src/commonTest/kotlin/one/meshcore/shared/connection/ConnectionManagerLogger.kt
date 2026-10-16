package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger

/** A ConnectionManager logger that records, so a persist test can vary only the fault. */
internal fun connectionManagerLogger(scope: CoroutineScope, recorded: MutableList<DebugLogEntryDto>) = PersistentLogger(
    scope,
    category = "ConnectionManager",
    record = { recorded += it },
)
