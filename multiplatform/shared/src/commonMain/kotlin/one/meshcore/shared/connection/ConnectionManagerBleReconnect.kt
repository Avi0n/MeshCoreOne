package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import one.meshcore.shared.transport.deviceHandlePrefix
import one.meshcore.shared.transport.shouldHoldAutoReconnectGatt

internal suspend fun ConnectionManager.advanceBleReconnect(
    deviceId: String,
    iteration: Int,
    connectFailures: Int,
    encryptionTimeoutFailures: Int,
): BleReconnectStep {
    val step = BleReconnectStep(
        iteration = iteration,
        connectFailures = connectFailures,
        encryptionTimeoutFailures = encryptionTimeoutFailures,
        finished = false,
    )
    if (!bluetoothConnectAuthorized()) {
        parkUnauthorizedBle(deviceId, connectGeneration)
        return step.copy(finished = true)
    }
    return when (bleReconnectGate(deviceId, iteration)) {
        BleReconnectGate.OtherApp -> step.copy(iteration = iteration + 1)
        BleReconnectGate.BreakerOpen -> step
        BleReconnectGate.Ready -> attemptBleReconnect(deviceId, step)
    }
}

private suspend fun ConnectionManager.bleReconnectGate(deviceId: String, iteration: Int): BleReconnectGate {
    val lastHandle = lastConnectionStore?.handle() ?: currentDeviceId
    return if (shouldDeferReconnectBecauseOtherApp(
            handle = deviceId,
            lastHandle = lastHandle,
            connectionState = mutableState.value.toDeviceConnectionState(),
            systemConnectedHandles = systemConnectedHandles(),
        )
    ) {
        logger.info(BLE_FOREGROUND_SKIP_OTHER_APP)
        delay(policy.watchdogBackoff(iteration))
        BleReconnectGate.OtherApp
    } else {
        if (!reconnectUiTimedOut && mutableState.value !is ConnectionState.BondLost) {
            mutableState.value = ConnectionState.Reconnecting(deviceId, iteration + 1)
        }
        // iOS watchdog sleeps then `connect(to:)` with force=false, so an open breaker
        // actually holds. Skip the GATT attempt until the cooldown elapses.
        if (!circuitBreaker.shouldAllow(force = false)) {
            delay(policy.watchdogBackoff(0))
            BleReconnectGate.BreakerOpen
        } else {
            BleReconnectGate.Ready
        }
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun ConnectionManager.attemptBleReconnect(deviceId: String, step: BleReconnectStep): BleReconnectStep {
    if (!bluetoothAdapterEnabled()) {
        // iOS watchdog stays `.disconnected` while the adapter is off so `.poweredOn`
        // can `connect(to:)` immediately. Sleeping here would hold Reconnecting and
        // `armBleReconnectIfWanted` would refuse the power-on edge.
        logger.info(BLE_WATCHDOG_SKIP_POWERED_OFF)
        mutableState.value = ConnectionState.Disconnected
        return step.copy(finished = true)
    }
    val generation = connectGeneration
    return try {
        bleConnectCeremony(deviceId, generation, autoReconnect = true)
        circuitBreaker.recordSuccess()
        if (step.iteration > 0) logger.info(bleReconnectionSucceededLine(step.iteration + 1))
        logger.info(BLE_CONNECTION_COMPLETE)
        step.copy(finished = true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        recoverBleReconnectFailure(deviceId, generation, step, error)
    }
}

private suspend fun ConnectionManager.recoverBleReconnectFailure(
    deviceId: String,
    generation: Long,
    step: BleReconnectStep,
    error: Throwable,
): BleReconnectStep {
    // A superseded attempt owns nothing: tearing down here would destroy the connection
    // that replaced it, and its failure says nothing about the live one.
    if (!ownsConnectAttempt(generation)) return step.copy(finished = true)
    if (isBluetoothUnauthorized(error)) {
        parkUnauthorizedBle(deviceId, generation)
        return step.copy(finished = true)
    }
    val rebuilt = rebuiltBleSessionOrNull(deviceId, generation, step, error)
    if (rebuilt != null) return rebuilt
    var gaveUp = false
    if (shouldHoldAutoReconnectGatt(error)) {
        // iOS holds `.autoReconnecting` on empty GATT with no error so the standing
        // pending connect is not cancelled. Wait the backoff with GATT up, then
        // replace the link for the next attempt.
        delay(policy.watchdogBackoff(step.iteration))
        gaveUp = !ownsConnectAttempt(generation)
    }
    return if (gaveUp) step.copy(finished = true) else recordBleReconnectMiss(deviceId, step, error)
}

private suspend fun ConnectionManager.rebuiltBleSessionOrNull(
    deviceId: String,
    generation: Long,
    step: BleReconnectStep,
    error: Throwable,
): BleReconnectStep? {
    if (error !is SessionRebuildException) return null
    logger.warning(bleRebuildSessionFailedLine(error.message ?: error.toString()))
    if (!rebuildSessionInPlace(deviceId, generation)) return null
    circuitBreaker.recordSuccess()
    if (step.iteration > 0) logger.info(bleReconnectionSucceededLine(step.iteration + 1))
    logger.info(BLE_CONNECTION_COMPLETE)
    return step.copy(finished = true)
}

private suspend fun ConnectionManager.recordBleReconnectMiss(
    deviceId: String,
    step: BleReconnectStep,
    error: Throwable,
): BleReconnectStep {
    tearDownCurrent(stopSession = false)
    val kind = connectFailureKind(error)
    var connectFailures = step.connectFailures + 1
    var encryptionTimeoutFailures = step.encryptionTimeoutFailures
    if (kind == ConnectFailureKind.ENCRYPTION_TIMEOUT) encryptionTimeoutFailures++
    val decision = policy.classifyConnectFailure(
        ConnectFailureFacts(
            kind = kind,
            connectFailures = connectFailures,
            encryptionTimeoutFailures = encryptionTimeoutFailures,
            lastBondVerificationEpochSeconds = bondVerificationEpochSeconds[deviceId],
            nowEpochSeconds = nowEpochSeconds(),
            appActive = isAppActive,
        ),
    )
    logger.warning(bleReconnectAttemptFailedLine(step.iteration + 1, error.message ?: error.toString()))
    if (decision == ConnectFailureDecision.ESCALATE_BOND_LOST) {
        mutableState.value = ConnectionState.BondLost(deviceId)
    }
    // A spent budget that did not escalate closes the window; the next tally re-accumulates
    // so a bond only tips to suspect after a fresh run of encryption timeouts.
    if (connectFailures >= policy.maxAutoReconnectConnectFailures) {
        connectFailures = 0
        encryptionTimeoutFailures = 0
    }
    // iOS `retryPendingConnect` re-issues `connect(..., enableAutoReconnect)` immediately;
    // didFailToConnect already consumed the pending connect. The 30/60/120s sleep is the
    // watchdog, used here only when the episode escalates to BondLost.
    if (decision == ConnectFailureDecision.RETRY) {
        logger.info(
            bleTransientConnectFailureReissueLine(
                connectFailures,
                policy.maxAutoReconnectConnectFailures,
                deviceHandlePrefix(deviceId),
            ),
        )
        yield()
    } else {
        delay(policy.watchdogBackoff(step.iteration))
    }
    return BleReconnectStep(
        iteration = step.iteration + 1,
        connectFailures = connectFailures,
        encryptionTimeoutFailures = encryptionTimeoutFailures,
        finished = false,
    )
}

private enum class BleReconnectGate {
    OtherApp,
    BreakerOpen,
    Ready,
}

internal data class BleReconnectStep(
    val iteration: Int,
    val connectFailures: Int,
    val encryptionTimeoutFailures: Int,
    val finished: Boolean,
)
