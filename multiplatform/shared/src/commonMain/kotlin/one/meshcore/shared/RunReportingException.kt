package one.meshcore.shared

import kotlinx.coroutines.CancellationException

/**
 * Runs [block]. Cancellation is rethrown as the same instance. Every other [Exception] is passed
 * to [onFailure], and that callback's result is returned. Every other throwable propagates,
 * including a throw from [onFailure].
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> runReportingException(onFailure: (Exception) -> T, block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    onFailure(error)
}

/**
 * Runs [block]. Cancellation is rethrown as the same instance. Every other throwable, including an
 * [Error], is passed to [onFailure], and that callback's result is returned. A throw from
 * [onFailure] propagates.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> runReportingThrowable(onFailure: suspend (Throwable) -> T, block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    onFailure(error)
}

/**
 * Runs [block]. Cancellation is rethrown as the same instance. An [Error] still propagates. Every
 * other [Exception] is discarded and [onFailure] supplies the result.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> runExceptionFallback(onFailure: () -> T, block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    onFailure()
}

/**
 * Runs [block]. Cancellation is rethrown as the same instance. Every other throwable, including an
 * [Error], is discarded and [onFailure] supplies the result.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> runThrowableResult(onFailure: () -> T, block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    onFailure()
}

/**
 * Runs [block]. Cancellation is rethrown as the same instance. Every other throwable is reported to
 * [onFailure], then [fallback] supplies the result. A throw from [onFailure] propagates, so
 * [fallback] does not run.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend inline fun <T> runThrowableFallback(
    onFailure: (Throwable) -> Unit,
    fallback: () -> T,
    block: suspend () -> T,
): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    onFailure(error)
    fallback()
}
