package one.meshcore.shared.persistence

import one.meshcore.shared.runLoggedException

/**
 * Logs [failure] and returns [fallback] when a Room read or write throws. The radio-session
 * observers keep collecting. Cancellation still stops the caller, and an error still propagates.
 */
suspend fun <T> runRoom(warning: (String) -> Unit, failure: String, fallback: () -> T, block: suspend () -> T): T =
    runLoggedException(warning, failure, fallback, block)
