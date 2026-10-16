package one.meshcore.shared.contact

import one.meshcore.shared.runThrowableFallback

/**
 * Runs one contact store side effect that must not abort the surrounding round.
 * Cancellation stops the caller. Every other failure is reported, then [fallback] continues the round.
 */
internal suspend inline fun <T> runLoggedContactEffect(
    onFailure: (Throwable) -> Unit,
    fallback: () -> T,
    block: suspend () -> T,
): T = runThrowableFallback(onFailure, fallback, block)
