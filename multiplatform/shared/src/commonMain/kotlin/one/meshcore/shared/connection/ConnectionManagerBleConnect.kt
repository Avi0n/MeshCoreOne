package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import one.meshcore.shared.transport.BluetoothPoweredOffException
import one.meshcore.shared.transport.BluetoothUnauthorizedException
import one.meshcore.shared.transport.MeshLinkConnectException

internal suspend fun ConnectionManager.runBleConnectAttempts(
    deviceId: String,
    generation: Long,
    forceReconnect: Boolean,
) {
    var lastError: Throwable? = null
    val registry = hasSystemPairingRegistry()
    val maxAttempts = connectRetryPolicy.attemptsFor(forceReconnect, registry)
    for (attempt in 1..maxAttempts) {
        throwIfSuperseded(generation, lastError)
        if (!bluetoothConnectAuthorized()) {
            throwUnauthorizedBleFailure(deviceId, generation, BluetoothUnauthorizedException())
        }
        if (!bluetoothAdapterEnabled()) {
            logger.info(BLE_WATCHDOG_SKIP_POWERED_OFF)
            tearDownCurrent(stopSession = false)
            mutableState.value = ConnectionState.Disconnected
            throw BluetoothPoweredOffException()
        }
        val failure = oneBleConnectAttempt(
            BleConnectAttempt(deviceId, generation, attempt, forceReconnect, registry, maxAttempts),
        )
        if (failure == null) return
        lastError = failure
    }
    noteCircuitFailure()
    mutableState.value = ConnectionState.Disconnected
    throw lastError ?: MeshLinkConnectException("Unknown error")
}

@Suppress("TooGenericExceptionCaught")
private suspend fun ConnectionManager.oneBleConnectAttempt(attempt: BleConnectAttempt): Throwable? = try {
    bleConnectCeremony(attempt.deviceId, attempt.generation)
    noteCircuitSuccess()
    if (attempt.attempt > 1) logger.info(bleReconnectionSucceededLine(attempt.attempt))
    logger.info(BLE_CONNECTION_COMPLETE)
    null
} catch (cancelled: CancellationException) {
    abandonCancelledConnect(attempt.generation)
    throw cancelled
} catch (error: Throwable) {
    recoverBleConnectFailure(error, attempt)
}

private suspend fun ConnectionManager.recoverBleConnectFailure(
    error: Throwable,
    attempt: BleConnectAttempt,
): Throwable {
    val failure = unwrappedConnectFailure(error)
    throwIfSuperseded(attempt.generation, failure)
    if (isBluetoothUnauthorized(failure)) {
        throwUnauthorizedBleFailure(attempt.deviceId, attempt.generation, failure)
    }
    val definitiveBond = connectFailureKind(failure) == ConnectFailureKind.DEFINITIVE_BOND
    if (!definitiveBond) {
        logger.warning(bleReconnectAttemptFailedLine(attempt.attempt, failure.message ?: failure.toString()))
    }
    val expectedHandle = currentDeviceId
    val expectedTransport = currentTransportType
    val expectedHost = wifiHost
    val expectedPort = wifiPort
    val expectedWants = wantsConnection
    // Ordinary retries keep this generation so the inner budget remains one connect(to:).
    val retiredGeneration = tearDownCurrent(stopSession = false, supersede = definitiveBond)
    fun ownsFailureExit(): Boolean = ownsEmptyBleFailureGraph(retiredGeneration) &&
        currentDeviceId == expectedHandle &&
        currentTransportType == expectedTransport &&
        wifiHost == expectedHost &&
        wifiPort == expectedPort &&
        wantsConnection == expectedWants
    if (!ownsFailureExit()) throw failure
    if (definitiveBond) {
        mutableState.value = ConnectionState.BondLost(attempt.deviceId)
        throw failure
    }
    mutableState.value = ConnectionState.Connecting(attempt.deviceId)
    if (attempt.attempt < attempt.maxAttempts) {
        delay(connectRetryPolicy.backoff(attempt.attempt, attempt.forceReconnect, attempt.hasSystemPairingRegistry))
    }
    return failure
}

private fun ConnectionManager.ownsEmptyBleFailureGraph(generation: Long): Boolean =
    ownsConnectAttempt(generation) && services == null && session == null && link == null

private fun unwrappedConnectFailure(error: Throwable): Throwable =
    if (error is SessionRebuildException) error.cause ?: error else error

private data class BleConnectAttempt(
    val deviceId: String,
    val generation: Long,
    val attempt: Int,
    val forceReconnect: Boolean,
    val hasSystemPairingRegistry: Boolean,
    val maxAttempts: Int,
)
