package one.meshcore.shared.connection

import one.meshcore.shared.runThrowableFallback

/**
 * Runs one connection persist or read. Cancellation stops the caller. Every other failure,
 * including an error, is reported and [fallback] lets the caller continue.
 */
internal suspend inline fun <T> runConnectionPersist(
    onFailure: (Throwable) -> Unit,
    fallback: () -> T,
    block: suspend () -> T,
): T = runThrowableFallback(onFailure, fallback, block)
