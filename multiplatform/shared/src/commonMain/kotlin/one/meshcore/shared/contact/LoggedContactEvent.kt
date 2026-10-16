package one.meshcore.shared.contact

import one.meshcore.shared.runReportingException
import one.meshcore.shared.settings.PersistentLogger

/**
 * Logs a contact-table event fault and keeps the collector alive. Cancellation still stops the
 * collector. The line keeps the thrown message, or the exception itself when it has none.
 */
internal suspend fun PersistentLogger.loggedContactEvent(failure: String, block: suspend () -> Unit) {
    runReportingException(
        onFailure = { thrown -> error("$failure: ${thrown.message ?: thrown}") },
    ) {
        block()
    }
}
