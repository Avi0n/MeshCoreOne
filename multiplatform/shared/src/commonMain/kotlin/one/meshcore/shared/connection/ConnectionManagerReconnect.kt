package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

// The BLE auto-reconnect loop. `iteration` drives the 30/60/120s backoff; a separate failure tally
// drives escalation. Each attempt uses `connectGatt(autoConnect)` so Android waits for the radio
// instead of a 10s direct connect. Handshake failure over live GATT rebuilds the session in place
// (iOS `rebuildSession`); empty discovery holds through the backoff. Only a definitive bond failure,
// or an encryption-timeout-dominated budget with no recently verified session, escalates to BondLost
// so guided re-pair appears; a fresh bond stamp graces the latter.
internal fun ConnectionManager.startBleReconnect(deviceId: String) {
    logger.info(BLE_WATCHDOG_ATTEMPTING)
    cancelReconnect()
    reconnectJob = scope.launch {
        // A bond verified in a previous process seeds the live grace before this process completes a
        // fresh handshake, so a cold rebuild's reconnect still shields a healthy radio at range's edge
        // instead of routing it to guided re-pair. Swift seeds the same stamp at launch wiring; here the
        // seed is lazy, at the one point that consumes it, and never overwrites a fresher live stamp.
        if (deviceId !in bondVerificationEpochSeconds) {
            lastConnectionStore?.bondVerificationEpochSeconds(deviceId)?.let {
                bondVerificationEpochSeconds[deviceId] = it
            }
        }
        var iteration = 0
        var connectFailures = 0
        var encryptionTimeoutFailures = 0
        // iOS `maxConnectingUIWindow`: stand the Connecting pill down while this loop keeps
        // retrying, so backup import and the status chrome are not blocked for the whole episode.
        restartReconnectUiTimeout(deviceId)
        try {
            while (wantsConnection && currentDeviceId == deviceId) {
                ensureActive()
                val step = advanceBleReconnect(
                    deviceId = deviceId,
                    iteration = iteration,
                    connectFailures = connectFailures,
                    encryptionTimeoutFailures = encryptionTimeoutFailures,
                )
                iteration = step.iteration
                connectFailures = step.connectFailures
                encryptionTimeoutFailures = step.encryptionTimeoutFailures
                if (step.finished) return@launch
            }
        } finally {
            reconnectUiTimeoutJob?.cancel()
            reconnectUiTimeoutJob = null
            // Pairing may cancel us between the loop's active check and its Reconnecting
            // publication. Clear that stale pill after the cancelled job has finished.
            val state = mutableState.value
            if (pairingInProgress && state is ConnectionState.Reconnecting && state.deviceId == deviceId) {
                mutableState.value = ConnectionState.Disconnected
            }
        }
    }
}

/** iOS `BLEReconnectionCoordinator.restartTimeout`: re-arm the Connecting pill from this tap. */
internal fun ConnectionManager.restartReconnectUiTimeout(deviceId: String) {
    reconnectUiTimeoutJob?.cancel()
    reconnectUiTimedOut = false
    reconnectUiTimeoutJob = scope.launch {
        delay(policy.maxConnectingUiWindow)
        if (!wantsConnection || currentDeviceId != deviceId) return@launch
        reconnectUiTimedOut = true
        if (mutableState.value is ConnectionState.Reconnecting) {
            logger.warning(bleReconnectUiTimeoutLine(policy.maxConnectingUiWindow.inWholeSeconds))
            mutableState.value = ConnectionState.Disconnected
        }
    }
}

/**
 * Retries app-start over the live GATT. iOS `retryRebuild` + `handleReconnectionFailure` preserve
 * the link through [ReconnectPolicy.maxRebuildFailuresPreservingLink] failures so a standing
 * `autoConnect` is not cancelled by a transient handshake timeout.
 */
internal suspend fun ConnectionManager.rebuildSessionInPlace(deviceId: String, generation: Long): Boolean {
    sessionRebuildDeviceId = deviceId
    try {
        return runRebuildAttempts(deviceId, generation)
    } finally {
        if (sessionRebuildDeviceId == deviceId) sessionRebuildDeviceId = null
    }
}

private suspend fun ConnectionManager.runRebuildAttempts(deviceId: String, generation: Long): Boolean {
    var failures = 1
    val budget = policy.maxRebuildFailuresPreservingLink
    var succeeded = false
    var stopped = false
    while (failures <= budget && !succeeded && !stopped) {
        logger.warning(bleRebuildPreservingLinkLine(failures, budget))
        delay(policy.sessionRebuildRetryDelay)
        when (oneRebuildAttempt(deviceId, generation)) {
            RebuildAttempt.Stop -> stopped = true
            RebuildAttempt.Done -> succeeded = true
            RebuildAttempt.Retry -> failures++
        }
    }
    if (!succeeded && !stopped) logger.error(bleRebuildBudgetExhaustedLine(failures))
    return succeeded
}

@Suppress("TooGenericExceptionCaught")
private suspend fun ConnectionManager.oneRebuildAttempt(deviceId: String, generation: Long): RebuildAttempt {
    if (!ownsConnectAttempt(generation) || !wantsConnection || currentDeviceId != deviceId) {
        return RebuildAttempt.Stop
    }
    val liveLink = link ?: return RebuildAttempt.Stop
    if (!liveLink.isConnected) return RebuildAttempt.Stop
    return try {
        val (selfInfo, capabilities) = handshakeOverExistingLink(deviceId, liveLink, generation)
        if (!ownsConnectAttempt(generation)) return RebuildAttempt.Stop
        completeBleAfterHandshake(deviceId, generation, selfInfo, capabilities)
        if (ownsConnectAttempt(generation)) RebuildAttempt.Done else RebuildAttempt.Stop
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (rebuild: SessionRebuildException) {
        logger.warning(bleRebuildRetryingLine(rebuild.message ?: rebuild.toString()))
        RebuildAttempt.Retry
    } catch (error: Throwable) {
        logger.warning(bleRebuildRetryingLine(error.message ?: error.toString()))
        RebuildAttempt.Stop
    }
}

// The WiFi reconnect loop: cooldown-gated between runs, bounded to a total window, capped-exponential
// backoff between attempts. A separate schedule from BLE (see [WiFiReconnectPolicy]), chosen by
// [currentTransportType] in [handleLinkDrop].
@Suppress("TooGenericExceptionCaught")
internal fun ConnectionManager.startWiFiReconnect(host: String, port: Int, handle: String) {
    val now = nowEpochSeconds()
    val cooldownSeconds = wifiPolicy.cooldown.inWholeMilliseconds / ConnectionManager.MILLIS_PER_SECOND
    val lastStart = lastWifiReconnectStartSeconds
    if (lastStart != null && now - lastStart < cooldownSeconds) {
        logger.warning(WIFI_RECONNECT_SUPPRESSED)
        mutableState.value = ConnectionState.Disconnected
        return
    }
    lastWifiReconnectStartSeconds = now
    logger.info(WIFI_STATE_CONNECTING_ON_DROP)
    cancelReconnect()
    reconnectJob = scope.launch {
        val start = nowEpochSeconds()
        val windowSeconds = wifiPolicy.window.inWholeMilliseconds / ConnectionManager.MILLIS_PER_SECOND
        var attempt = 0
        while (wantsConnection && currentDeviceId == handle) {
            if (nowEpochSeconds() - start > windowSeconds) {
                logger.info(WIFI_RECONNECT_TIMEOUT)
                tearDownCurrent(stopSession = false)
                mutableState.value = ConnectionState.Disconnected
                return@launch
            }
            attempt++
            logger.info(wifiReconnectAttemptLine(attempt))
            mutableState.value = ConnectionState.Reconnecting(handle, attempt)
            val generation = connectGeneration
            try {
                wifiConnectCeremony(host, port, handle, generation)
                circuitBreaker.recordSuccess()
                logger.info(WIFI_RECONNECT_SUCCEEDED)
                return@launch
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // A superseded attempt owns nothing; see the BLE loop.
                if (!ownsConnectAttempt(generation)) return@launch
                logger.warning(wifiReconnectFailedLine(error.message ?: error.toString()))
                tearDownCurrent(stopSession = false)
                delay(wifiPolicy.backoff(attempt))
            }
        }
    }
}

internal fun ConnectionManager.cancelReconnect() {
    reconnectUiTimeoutJob?.cancel()
    reconnectUiTimeoutJob = null
    reconnectJob?.cancel()
    reconnectJob = null
}

private enum class RebuildAttempt {
    Stop,
    Done,
    Retry,
}
