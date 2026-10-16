package one.meshcore.shared.connection

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Persist path for a live-session bond refresh: snapshot the epoch, re-validate that the BLE
 * session is still up and already has a stamp, then require the same epoch before writing the
 * store so a forget that landed during the await cannot resurrect the cross-launch shield.
 * Never creates a stamp — only a completed handshake is evidence the bond verified.
 */
internal suspend fun ConnectionManager.persistBondRefreshIfStillValid(deviceId: String) {
    val epoch = bondRefreshPersistEpoch
    val refreshed = refreshedBondStamp(deviceId, epoch) ?: return
    applyLastConnectionPersist(
        persist = { lastConnectionStore?.persistBondVerification(deviceId, refreshed) },
        logger = logger,
        failLine = FAILED_PERSIST_BOND_VERIFICATION,
    )
}

private fun ConnectionManager.refreshedBondStamp(deviceId: String, epoch: Long): Double? {
    if (!shouldPersistBondRefresh(deviceId)) return null
    if (!policy.refreshBondVerification(bondVerificationEpochSeconds, deviceId, nowEpochSeconds())) return null
    if (epoch != bondRefreshPersistEpoch) return null
    return bondVerificationEpochSeconds[deviceId]
}

/**
 * True when the last-connection slot is empty (forget cleared it). A prefs-read fault is treated
 * as not-forgotten so a DataStore error cannot skip reconnect. Cancellation still stops the caller.
 */
internal suspend fun ConnectionManager.lastConnectionForgotten(): Boolean {
    val store = lastConnectionStore ?: return false
    return readLastConnectionForgotten(readDeviceId = store::deviceId, warning = logger::warning)
}

internal fun ConnectionManager.shouldPersistBondRefresh(deviceId: String): Boolean =
    currentTransportType == TransportType.BLUETOOTH &&
        currentDeviceId == deviceId &&
        session != null &&
        state.value.isLinkUp &&
        deviceId in bondVerificationEpochSeconds

internal fun ConnectionManager.startBondRefreshLoop(deviceId: String) {
    cancelBondRefreshLoop()
    bondRefreshJob = scope.launch {
        while (true) {
            delay(policy.bondRefreshInterval)
            persistBondRefreshIfStillValid(deviceId)
        }
    }
}

internal fun ConnectionManager.cancelBondRefreshLoop() {
    bondRefreshJob?.cancel()
    bondRefreshJob = null
}
