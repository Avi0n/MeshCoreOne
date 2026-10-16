package one.meshcore.shared.prefs

import one.meshcore.shared.runLoggedException

/**
 * Logs [failure] and returns [fallback] when a preferences read or write throws. One failed key
 * must not cancel the caller. Cancellation still stops the caller, and an error still propagates.
 */
suspend fun <T> runPreferences(
    warning: (String) -> Unit,
    failure: String,
    fallback: () -> T,
    block: suspend () -> T,
): T = runLoggedException(warning, failure, fallback, block)
