package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The connection-UI sub-flag machine, ported from the iOS `ConnectionUIState`. It owns the timed pills,
 * the sync-activity counter, the node-storage-full flag, and connection/pairing alert routing that feed
 * the status pill and the alerts.
 *
 * The timers are the "clock/scheduler seam": each auto-hide runs as a cancellable coroutine on the
 * injected [scope], so tests drive them with a virtual-time dispatcher instead of wall-clock waits.
 *
 * The screen-reader announcements the iOS class posts inline belong to the UI layer here: `StatusPill`
 * speaks the sync-failed state through the live region hoisted out of its animation, and the shell
 * speaks a lost link off the connection rung. That keeps this state machine free of string resources.
 */
class ConnectionUiState private constructor(private val parts: Parts) :
    ConnectionUiPicker by parts.picker,
    ConnectionUiPills by parts.pills,
    ConnectionUiSync by parts.sync,
    ConnectionUiAlerts by parts.alerts {
    constructor(scope: CoroutineScope) : this(Parts(scope))

    /** Whether the "Ready" toast is showing (briefly, after a successful sync completes). */
    val showReadyToast: StateFlow<Boolean> = parts.showReadyToast

    /** Whether the "Sync Failed" pill is showing. */
    val syncFailedPillVisible: StateFlow<Boolean> = parts.syncFailedPillVisible

    /** Whether the "Disconnected" pill is showing (after a 1s delay that suppresses reconnect flashes). */
    val disconnectedPillVisible: StateFlow<Boolean> = parts.disconnectedPillVisible

    /** Count of in-flight contacts/channels sync operations; drives the syncing pill. */
    val syncActivityCount: StateFlow<Int> = parts.syncActivityCount

    /** The current sync phase, for deferring non-essential settings reads. */
    val currentSyncPhaseFlow: StateFlow<SyncPhase?> = parts.currentSyncPhaseFlow
    var currentSyncPhase: SyncPhase?
        get() = parts.syncPhase.value
        set(value) {
            parts.syncPhase.value = value
        }

    /** Whether the radio reports its node storage is full. */
    val isNodeStorageFullFlow: StateFlow<Boolean> = parts.isNodeStorageFullFlow
    var isNodeStorageFull: Boolean
        get() = parts.nodeStorageFull.value
        set(value) {
            parts.nodeStorageFull.value = value
        }

    /** Whether a user-initiated connection attempt is in flight (drives spinners and disabled buttons). */
    val isBusyFlow: StateFlow<Boolean> = parts.isBusyFlow
    var isBusy: Boolean
        get() = parts.busy.value
        set(value) {
            parts.busy.value = value
        }

    /** The connection/pairing alert to present, or `null`. */
    val connectionAlert: StateFlow<ConnectionAlert?> = parts.connectionAlert

    /**
     * Set by Remove-and-Try-Again so the shell re-opens the connect sheet. iOS
     * `shouldShowPickerOnForeground`; Android is already foreground, so the shell consumes it at once.
     */
    val shouldShowConnectPicker: StateFlow<Boolean> = parts.shouldShowConnectPicker

    companion object {
        /** How long the "Ready" toast stays up. */
        val READY_TOAST_DURATION: Duration = 2.seconds

        /** How long the "Sync Failed" pill stays up. */
        val SYNC_FAILED_PILL_DURATION: Duration = 7.seconds

        /** Delay before the disconnected pill appears, suppressing flashes during brief reconnects. */
        val DISCONNECTED_PILL_DELAY: Duration = 1.seconds
    }

    private class Parts(private val scope: CoroutineScope) {
        private val readyToast = MutableStateFlow(false)
        private var readyToastJob: Job? = null
        val showReadyToast: StateFlow<Boolean> = readyToast.asStateFlow()

        private val syncFailedPill = MutableStateFlow(false)
        private var syncFailedPillJob: Job? = null
        val syncFailedPillVisible: StateFlow<Boolean> = syncFailedPill.asStateFlow()

        private val disconnectedPill = MutableStateFlow(false)
        private var disconnectedPillJob: Job? = null
        val disconnectedPillVisible: StateFlow<Boolean> = disconnectedPill.asStateFlow()

        private val syncActivity = MutableStateFlow(0)
        val syncActivityCount: StateFlow<Int> = syncActivity.asStateFlow()

        val syncPhase = MutableStateFlow<SyncPhase?>(null)
        val currentSyncPhaseFlow: StateFlow<SyncPhase?> = syncPhase.asStateFlow()

        val nodeStorageFull = MutableStateFlow(false)
        val isNodeStorageFullFlow: StateFlow<Boolean> = nodeStorageFull.asStateFlow()

        val busy = MutableStateFlow(false)
        val isBusyFlow: StateFlow<Boolean> = busy.asStateFlow()

        private val alert = MutableStateFlow<ConnectionAlert?>(null)
        val connectionAlert: StateFlow<ConnectionAlert?> = alert.asStateFlow()

        private val showConnectPicker = MutableStateFlow(false)
        private val companionPairingOnPicker = MutableStateFlow(false)
        val shouldShowConnectPicker: StateFlow<Boolean> = showConnectPicker.asStateFlow()

        val picker: ConnectionUiPicker = Picker()
        val pills: ConnectionUiPills = Pills()
        val sync: ConnectionUiSync = Sync()
        val alerts: ConnectionUiAlerts = Alerts()

        private inner class Picker : ConnectionUiPicker {
            override fun requestConnectPicker() {
                companionPairingOnPicker.value = true
                showConnectPicker.value = true
            }

            override fun consumeConnectPicker() {
                showConnectPicker.value = false
            }

            override fun consumeCompanionPairingOnPicker(): Boolean {
                val start = companionPairingOnPicker.value
                companionPairingOnPicker.value = false
                return start
            }
        }

        private inner class Pills : ConnectionUiPills {
            override fun showReadyToastBriefly() {
                readyToastJob?.cancel()
                readyToast.value = true
                readyToastJob = scope.launch {
                    delay(ConnectionUiState.READY_TOAST_DURATION)
                    readyToast.value = false
                }
            }

            override fun hideReadyToast() {
                readyToastJob?.cancel()
                readyToastJob = null
                readyToast.value = false
            }

            override fun showSyncFailedPill() {
                syncFailedPillJob?.cancel()
                syncFailedPill.value = true
                syncFailedPillJob = scope.launch {
                    delay(ConnectionUiState.SYNC_FAILED_PILL_DURATION)
                    syncFailedPill.value = false
                }
            }

            override fun hideSyncFailedPill() {
                syncFailedPillJob?.cancel()
                syncFailedPillJob = null
                syncFailedPill.value = false
            }

            override fun updateDisconnectedPillState(
                connectionState: DeviceConnectionState,
                lastConnectedDeviceId: String?,
                shouldSuppressDisconnectedPill: Boolean,
            ) {
                disconnectedPillJob?.cancel()
                if (connectionState != DeviceConnectionState.DISCONNECTED ||
                    lastConnectedDeviceId == null ||
                    shouldSuppressDisconnectedPill
                ) {
                    disconnectedPill.value = false
                    return
                }
                disconnectedPillJob = scope.launch {
                    delay(ConnectionUiState.DISCONNECTED_PILL_DELAY)
                    disconnectedPill.value = true
                }
            }

            override fun hideDisconnectedPill() {
                disconnectedPillJob?.cancel()
                disconnectedPillJob = null
                disconnectedPill.value = false
            }
        }

        private inner class Sync : ConnectionUiSync {
            override fun syncStarted() {
                syncActivity.value += 1
            }

            override fun syncEnded(succeeded: Boolean) {
                if (syncActivity.value <= 0) return
                syncActivity.value -= 1
                if (syncActivity.value == 0 && succeeded) pills.showReadyToastBriefly()
            }

            override fun handleDisconnect(
                connectionState: DeviceConnectionState,
                lastConnectedDeviceId: String?,
                shouldSuppressDisconnectedPill: Boolean,
            ) {
                syncActivity.value = 0
                syncPhase.value = null
                pills.hideReadyToast()
                nodeStorageFull.value = false
                pills.updateDisconnectedPillState(
                    connectionState,
                    lastConnectedDeviceId,
                    shouldSuppressDisconnectedPill,
                )
            }
        }

        private inner class Alerts : ConnectionUiAlerts {
            override fun presentConnectionFailure(message: String?) {
                alert.value = ConnectionAlert.Generic(message)
            }

            override fun presentSavedDeviceConnectFailure(deviceId: String, error: SavedDeviceConnectError) {
                when (error) {
                    is SavedDeviceConnectError.BluetoothUnauthorized ->
                        alert.value = ConnectionAlert.BluetoothUnauthorized
                    is SavedDeviceConnectError.OtherApp -> alert.value = ConnectionAlert.OtherApp(deviceId)
                    is SavedDeviceConnectError.AuthenticationFailed ->
                        presentPairingFailure(PairingFailure.ConnectionFailed(deviceId, isAuthenticationFailure = true))
                    is SavedDeviceConnectError.Other -> presentConnectionFailure(error.message)
                }
            }

            override fun presentFreshPairingFailure(error: PairingFailure) {
                if (error is PairingFailure.ConnectionFailed && error.isAuthenticationFailure) {
                    alert.value = ConnectionAlert.Pairing(error.deviceId, PairingFailureKind.PIN_REJECTED)
                    return
                }
                presentPairingFailure(error)
            }

            override fun presentPairingFailure(error: PairingFailure) {
                alert.value = when (error) {
                    is PairingFailure.DeviceConnectedToOtherApp -> ConnectionAlert.OtherApp(error.deviceId)
                    is PairingFailure.ConnectionFailed -> {
                        val kind = if (error.isAuthenticationFailure) {
                            PairingFailureKind.AUTHENTICATION
                        } else {
                            PairingFailureKind.TRANSIENT
                        }
                        ConnectionAlert.Pairing(error.deviceId, kind)
                    }
                }
            }

            override fun dismissAlert() {
                alert.value = null
            }

            override fun clearPairingFailure() {
                if (alert.value is ConnectionAlert.Pairing) alert.value = null
            }
        }
    }
}

/** Remove-and-Try-Again opens the connect sheet and asks the sheet to start companion pairing. */
interface ConnectionUiPicker {
    fun requestConnectPicker()
    fun consumeConnectPicker()

    /**
     * Remove-and-Try-Again must reopen Companion Device Manager (iOS `startDeviceScan` /
     * `pairNewDevice`), not only the connect sheet. Consumed by the sheet or onboarding pair step.
     */
    fun consumeCompanionPairingOnPicker(): Boolean
}

/** Timed ready, sync-failed, and disconnected pills. */
interface ConnectionUiPills {
    /**
     * Shows the "Ready" toast, auto-hiding after [ConnectionUiState.READY_TOAST_DURATION]. Re-calling
     * resets the timer.
     */
    fun showReadyToastBriefly()

    /** Hides the ready toast immediately (on disconnect). */
    fun hideReadyToast()

    /** Shows the "Sync Failed" pill, auto-hiding after [ConnectionUiState.SYNC_FAILED_PILL_DURATION]. */
    fun showSyncFailedPill()

    /** Hides the sync-failed pill immediately (when resync succeeds). */
    fun hideSyncFailedPill()

    /**
     * Shows the disconnected pill after [ConnectionUiState.DISCONNECTED_PILL_DELAY], but only while
     * disconnected with a last-connected device and not suppressed by an explicit user disconnect;
     * otherwise hides it.
     */
    fun updateDisconnectedPillState(
        connectionState: DeviceConnectionState,
        lastConnectedDeviceId: String?,
        shouldSuppressDisconnectedPill: Boolean,
    )

    /** Hides the disconnected pill immediately (when a connection starts). */
    fun hideDisconnectedPill()
}

/** In-flight sync counter and the full reset when services drop. */
interface ConnectionUiSync {
    /** Records a sync operation starting. */
    fun syncStarted()

    /**
     * Records a sync operation ending. Guarded against double-decrement (a WiFi drop and a device switch
     * can both fire during sync). When the count reaches zero on success, shows the ready toast.
     */
    fun syncEnded(succeeded: Boolean)

    /** Resets all connection-UI state when services become unavailable. */
    fun handleDisconnect(
        connectionState: DeviceConnectionState,
        lastConnectedDeviceId: String?,
        shouldSuppressDisconnectedPill: Boolean,
    )
}

/** Connection and pairing alert routing. */
interface ConnectionUiAlerts {
    /**
     * Routes a generic (non-pairing) failure to an OK-only alert, clearing any pairing state so a prior
     * pairing failure cannot leak a destructive recovery onto an unrelated failure.
     */
    fun presentConnectionFailure(message: String?)

    /** Routes a failure from a user-initiated connect to an already-paired radio. */
    fun presentSavedDeviceConnectFailure(deviceId: String, error: SavedDeviceConnectError)

    /**
     * Routes a failure from a fresh BLE pairing attempt. A rejected authentication on a fresh pair
     * becomes [PairingFailureKind.PIN_REJECTED]; every other failure shares the standard routing.
     */
    fun presentFreshPairingFailure(error: PairingFailure)

    /** Routes a [PairingFailure] to the correct alert so every pairing catch site produces identical UX. */
    fun presentPairingFailure(error: PairingFailure)

    /** Dismisses the current alert. */
    fun dismissAlert()

    /**
     * Drops a latched pairing-failure alert only. A live connection must not keep Remove-and-Try-Again
     * over a working bond (iOS `clearPairingFailure`). A generic or other-app alert is left alone.
     */
    fun clearPairingFailure()
}
