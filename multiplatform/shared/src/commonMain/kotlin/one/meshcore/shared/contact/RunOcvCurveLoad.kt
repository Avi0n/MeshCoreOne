package one.meshcore.shared.contact

import one.meshcore.shared.runExceptionFallback

/**
 * One battery-curve load. Cancellation is rethrown with no extra side effect. An error still
 * propagates. Every other failure is reported by the caller.
 */
internal suspend fun runOcvCurveLoad(onFailure: () -> Unit, block: suspend () -> Unit) {
    runExceptionFallback(onFailure) { block() }
}
