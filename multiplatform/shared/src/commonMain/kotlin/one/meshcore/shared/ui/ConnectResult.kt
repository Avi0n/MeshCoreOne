package one.meshcore.shared.ui

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.connection.DeviceConnectedToOtherAppException

internal data class ConnectResult(val success: Boolean, val message: String? = null, val otherApp: Boolean = false)

internal fun connectFailureMessage(
    error: Throwable,
    transportErrorText: (Throwable) -> String?,
    fallback: (Throwable) -> String,
): String = transportErrorText(error) ?: fallback(error)

@Suppress("TooGenericExceptionCaught")
internal suspend fun runConnect(
    connect: suspend () -> Unit,
    transportErrorText: (Throwable) -> String?,
    fallbackErrorText: (Throwable) -> String,
): ConnectResult = try {
    connect()
    ConnectResult(success = true)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: DeviceConnectedToOtherAppException) {
    ConnectResult(success = false, otherApp = true)
} catch (error: Exception) {
    ConnectResult(
        success = false,
        message = connectFailureMessage(error, transportErrorText, fallbackErrorText),
    )
}
