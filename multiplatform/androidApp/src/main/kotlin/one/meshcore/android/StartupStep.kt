package one.meshcore.android

import one.meshcore.shared.runLoggedException

/**
 * Logs [failure] and returns [fallback] when a process-start step throws. Demo image seed and the
 * connection-header mirror keep going. Cancellation still stops the caller, and an error still propagates.
 */
internal suspend fun <T> runStartupStep(
    warning: (String) -> Unit,
    failure: String,
    fallback: () -> T,
    block: suspend () -> T,
): T = runLoggedException(warning, failure, fallback, block)

/** Runs unrelated process refreshes through separate fault boundaries so one failure cannot skip the rest. */
internal suspend fun runStartupStepsIndependently(
    warning: (String) -> Unit,
    failure: String,
    vararg steps: suspend () -> Unit,
) {
    steps.forEach { step ->
        runStartupStep(warning, failure, fallback = {}, block = step)
    }
}
