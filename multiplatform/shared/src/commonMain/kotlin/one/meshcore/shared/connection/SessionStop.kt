package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException

/**
 * Runs service teardown and returns the same cancellation so session close still happens.
 * Any other failure still propagates.
 */
internal suspend fun captureTearDownCancellation(tearDown: suspend () -> Unit): CancellationException? = try {
    tearDown()
    null
} catch (cancelled: CancellationException) {
    cancelled
}

/**
 * Stops a session or its auto-fetch drain. A thrown failure is logged and comes back null so
 * close and the rest of teardown still run. The same cancellation is returned for the caller
 * to rethrow after that cleanup.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun captureSessionStop(warning: (String) -> Unit, stop: suspend () -> Unit): CancellationException? =
    try {
        stop()
        null
    } catch (cancelled: CancellationException) {
        cancelled
    } catch (error: Throwable) {
        warning("$SESSION_STOP_FAILED: ${error.message ?: error}")
        null
    }

internal const val SESSION_STOP_FAILED = "Session stop failed"
