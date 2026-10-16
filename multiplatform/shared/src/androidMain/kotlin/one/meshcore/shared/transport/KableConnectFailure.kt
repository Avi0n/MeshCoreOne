package one.meshcore.shared.transport

import com.juul.kable.GattStatusException
import kotlinx.coroutines.CancellationException
import one.meshcore.shared.runReportingThrowable

/**
 * Reclassifies a Kable connect failure. A dead-bond GATT status becomes [BondLostException]. An
 * encryption-timeout-class status becomes [EncryptionTimeoutException]. A missing UART characteristic
 * stays [CharacteristicNotFoundException]. Anything else keeps the original failure.
 */
internal fun mapKableConnectFailure(error: Throwable, disconnectedGattStatus: () -> Int?): Exception? = when (error) {
    is CancellationException -> error
    is BluetoothUnauthorizedException -> error
    is SecurityException -> BluetoothUnauthorizedException(error)
    is GattStatusException -> classifyGattConnectFailure(error.status)
    else -> disconnectedGattStatus()?.let(::classifyGattConnectFailure)
}

/** A missing notify during auto-reconnect holds the link. Any other RX failure stays the original error. */
internal fun rxNotifyFailure(error: Throwable, holdEmptyGatt: Boolean): Throwable = when {
    error is CancellationException || error is BluetoothUnauthorizedException -> error
    error is SecurityException -> BluetoothUnauthorizedException(error)
    holdEmptyGatt -> NotificationsNotEnabledException(error.message ?: error.toString(), error)
    else -> error
}

@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> runKableConnect(disconnectedGattStatus: () -> Int?, block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (missing: CharacteristicNotFoundException) {
    throw missing
} catch (error: Exception) {
    throw mapKableConnectFailure(error, disconnectedGattStatus) ?: error
} catch (error: Error) {
    throw mapKableConnectFailure(error, disconnectedGattStatus) ?: error
}

@Suppress("RedundantSuspendModifier")
internal suspend fun <T> runKableObserved(onFailure: suspend (Throwable) -> T, block: suspend () -> T): T =
    runReportingThrowable(onFailure, block)
