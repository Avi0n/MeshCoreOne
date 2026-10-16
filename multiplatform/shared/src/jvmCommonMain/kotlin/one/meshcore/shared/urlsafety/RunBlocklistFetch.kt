package one.meshcore.shared.urlsafety

import one.meshcore.shared.runExceptionFallback

/**
 * One blocklist HTTP read. Cancellation is rethrown. An error still propagates. Every other
 * failure uses the caller's fallback so a dead mirror does not replace the list.
 */
internal suspend fun <T> runBlocklistFetch(onFailure: () -> T, block: suspend () -> T): T =
    runExceptionFallback(onFailure, block)
