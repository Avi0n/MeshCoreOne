package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException

/**
 * Runs a contact radio command. A typed [ContactServiceError] is rethrown, a firmware code uses
 * [mapDeviceCode], and every other failure becomes [ContactServiceError.SessionError].
 * Cancellation still stops the caller.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend inline fun <T> runContactSession(
    noinline mapDeviceCode: ((Int) -> ContactServiceError?)?,
    block: () -> T,
): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    throw contactSessionFailure(error, mapDeviceCode)
}

internal fun contactSessionFailure(
    error: Throwable,
    mapDeviceCode: ((Int) -> ContactServiceError?)?,
): ContactServiceError = when (error) {
    is ContactServiceError -> error
    is ContactDeviceException ->
        mapDeviceCode?.invoke(error.firmwareCode) ?: ContactServiceError.SessionError(error)
    else -> ContactServiceError.SessionError(error)
}
