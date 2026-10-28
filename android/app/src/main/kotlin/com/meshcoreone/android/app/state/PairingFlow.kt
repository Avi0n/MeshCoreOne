// PortedFrom: MC1/State/AppState+DeviceActions.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.connectivity.pairing.DevicePairingError
import com.meshcoreone.android.core.connectivity.pairing.PairingError
import com.meshcoreone.android.core.connectivity.pairing.SystemPairedAccessory
import com.meshcoreone.android.core.connectivity.pairing.SystemPairingSetupPrompt
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.runtime.DeadlineClock
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.runtime.SystemRuntimeClock
import com.meshcoreone.android.core.ui.UiText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Guided pairing and system-registry setup flow (Swift `AppState+DeviceActions`). The system-registry
 * "Forget" prompts are the CompanionDeviceManager associations that have no saved device row; a rejected
 * picker retries once on the next foreground.
 */
class PairingFlow(
    private val connection: AppConnectionPort,
    private val ui: ConnectionUiState,
    private val scope: CoroutineScope,
    private val clock: DeadlineClock = SystemRuntimeClock(),
    /** Re-wires app state after a successful pairing connect (Swift `wireServicesIfConnected`). */
    private val wireServices: suspend () -> Unit,
    /** Ends the live session before pairing a different radio (Swift `disconnect(reason: .switchingDevice)`). */
    private val disconnect: suspend (RuntimeDisconnectReason) -> Unit,
    /** Runs after a fresh pairing connects (Swift appends the onboarding region step). */
    private val onFreshPairingCompleted: () -> Unit = {},
    private val freshPairingForegroundRetryDelay: Duration = 400.milliseconds,
) {
    @Volatile var isConfirmingSystemPairingSetup: Boolean = false
        private set
    @Volatile var isFreshPairingForegroundRetry: Boolean = false
        private set
    private var setupConfirmationJob: Job? = null
    private var foregroundRetryJob: Job? = null
    private var foregroundPairingJob: Job? = null
    private var freshPairingCompletion = 0L

    /** Returns true only after a fresh pairing completed; an existing live radio is not consent or success. */
    suspend fun startDeviceScanAndAwait(): Boolean {
        val completionBefore = freshPairingCompletion
        val scan = startDeviceScan()
        try {
            scan.join()
            ui.state.first {
                !it.isBusy && it.pendingSystemPairingSetup == null && it.queuedSystemPairingSetup == null &&
                    !it.shouldCompleteFreshPairingOnForeground
            }
            return freshPairingCompletion != completionBefore
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                scan.cancelAndJoin()
                setupConfirmationJob?.cancelAndJoin()
                foregroundRetryJob?.cancelAndJoin()
                foregroundPairingJob?.cancelAndJoin()
                cancelSystemPairingSetup()
                ui.isBusy = false
            }
            throw cancelled
        }
    }

    /** Start device scan/pairing. Returns the flow's job so callers and tests can await completion. */
    fun startDeviceScan(): Job {
        if (ui.pendingSystemPairingSetup != null || isConfirmingSystemPairingSetup) return scope.launch {}
        ui.hideDisconnectedPill()
        ui.failedPairingDeviceId = null
        ui.isBusy = true
        connection.isPairingFlowActive = true
        return scope.launch {
            try {
                withPairingFlowErrorHandling {
                    val pending = connection.systemAccessoriesMissingDeviceRecord()
                    if (pending.isNotEmpty()) {
                        if (ui.pendingSystemPairingSetup == null) {
                            ui.pendingSystemPairingSetup = SystemPairingSetupPrompt(
                                pending.map { SystemPairedAccessory(it.id, it.name) },
                            )
                        }
                        return@withPairingFlowErrorHandling
                    }
                    completeFreshPairing()
                }
            } finally {
                if (ui.pendingSystemPairingSetup == null && ui.queuedSystemPairingSetup == null &&
                    !ui.shouldCompleteFreshPairingOnForeground
                ) {
                    connection.isPairingFlowActive = false
                }
                ui.isBusy = false
            }
        }
    }

    /** Device-selection sheet left the hierarchy: start a queued scan or present the queued setup prompt. */
    fun handleDeviceSelectionSheetDismissed() {
        if (ui.queuedDeviceScanAfterSelectionDismiss) {
            ui.queuedDeviceScanAfterSelectionDismiss = false
            ui.queuedSystemPairingSetup = null
            connection.isPairingFlowActive = true
            scope.launch {
                connection.stopBleScanning()
                startDeviceScan()
            }
            return
        }
        val queued = ui.queuedSystemPairingSetup ?: return
        ui.queuedSystemPairingSetup = null
        if (ui.pendingSystemPairingSetup != null) return
        connection.isPairingFlowActive = true
        ui.pendingSystemPairingSetup = queued
    }

    fun cancelSystemPairingSetup() {
        foregroundRetryJob?.cancel()
        foregroundRetryJob = null
        foregroundPairingJob?.cancel()
        ui.shouldCompleteFreshPairingOnForeground = false
        isFreshPairingForegroundRetry = false
        connection.isPairingFlowActive = false
        ui.queuedSystemPairingSetup = null
        ui.pendingSystemPairingSetup = null
    }

    fun handleSystemPairingSetupSheetDismissed() {
        if (isConfirmingSystemPairingSetup) return
        if (ui.pendingSystemPairingSetup != null) return
        cancelSystemPairingSetup()
    }

    /** Forgets the pending associations, then opens the picker. Pending clears first so the system prompt is the only dialog. */
    fun confirmSystemPairingSetup(): Job? {
        if (isConfirmingSystemPairingSetup) return null
        val prompt = ui.pendingSystemPairingSetup ?: return null
        isConfirmingSystemPairingSetup = true
        connection.isPairingFlowActive = true
        ui.shouldShowPickerOnForeground = false
        ui.isBusy = true
        ui.pendingSystemPairingSetup = null
        return scope.launch {
            try {
                withPairingFlowErrorHandling {
                    connection.removeSystemAccessoriesMissingDeviceRecord(prompt.accessories.map { it.id })
                    completeFreshPairing()
                }
            } finally {
                isConfirmingSystemPairingSetup = false
                if (!ui.shouldCompleteFreshPairingOnForeground) connection.isPairingFlowActive = false
                ui.isBusy = false
            }
        }.also { setupConfirmationJob = it }
    }

    private suspend fun completeFreshPairing() {
        if (connection.connectionState != DeviceConnectionState.DISCONNECTED) {
            disconnect(RuntimeDisconnectReason.SWITCHING_DEVICE)
        }
        connection.pairNewDevice()
        wireServices()
        onFreshPairingCompleted()
        freshPairingCompletion++
    }

    private suspend fun withPairingFlowErrorHandling(work: suspend () -> Unit) {
        try {
            work()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: DevicePairingError.Cancelled) {
            // Picker dismissed, or the system removal was declined. Do not re-prompt.
            ui.shouldCompleteFreshPairingOnForeground = false
            isFreshPairingForegroundRetry = false
        } catch (failure: DevicePairingError.AlreadyInProgress) {
            // A pairing flow is already running; the re-entrant request is ignored.
        } catch (failure: DevicePairingError.PickerUnavailable) {
            handlePickerUnavailableAfterForget()
        } catch (failure: PairingError) {
            ui.presentFreshPairingFailure(failure)
        } catch (failure: Exception) {
            ui.presentConnectionFailure(ConnectionUiState.userFacingMessage(failure))
        }
    }

    /** The system rejected the picker; retry once on the next activation. A second rejection is a real failure. */
    private fun handlePickerUnavailableAfterForget() {
        if (isFreshPairingForegroundRetry) {
            isFreshPairingForegroundRetry = false
            ui.shouldCompleteFreshPairingOnForeground = false
            connection.isPairingFlowActive = false
            ui.presentConnectionFailure(UiText.Resource(AppLocalizableStrings.errorAccessorySetupPickerRestricted))
            return
        }
        scheduleFreshPairingOnForeground()
    }

    private fun scheduleFreshPairingOnForeground() {
        ui.shouldCompleteFreshPairingOnForeground = true
        connection.isPairingFlowActive = true
        foregroundRetryJob?.cancel()
        foregroundRetryJob = scope.launch {
            clock.sleep(freshPairingForegroundRetryDelay)
            resumeFreshPairingIfNeeded()
        }
    }

    suspend fun resumeFreshPairingIfNeeded() = coroutineScope {
        if (!ui.shouldCompleteFreshPairingOnForeground) return@coroutineScope
        foregroundPairingJob = coroutineContext[Job]
        ui.isBusy = true
        ui.shouldCompleteFreshPairingOnForeground = false
        isFreshPairingForegroundRetry = true
        connection.isPairingFlowActive = true
        try {
            withPairingFlowErrorHandling { completeFreshPairing() }
        } finally {
            foregroundPairingJob = null
            isFreshPairingForegroundRetry = false
            if (!ui.shouldCompleteFreshPairingOnForeground) connection.isPairingFlowActive = false
            ui.isBusy = false
        }
    }

    /** Removes a device that failed pairing (wrong PIN) and retries automatically. */
    fun removeFailedPairingAndRetry(): Job? {
        val deviceId = ui.failedPairingDeviceId ?: return null
        return scope.launch {
            connection.removeFailedPairing(deviceId)
            ui.failedPairingDeviceId = null
            if (connection.hasSystemPairingRegistry) {
                // Removing the association can bounce the scene; wait for the next activation.
                ui.shouldShowPickerOnForeground = true
            } else {
                // No registry confirmation, so activation will not re-fire.
                startDeviceScan()
            }
        }
    }

    /** Retries the device that just failed without removing the bond (transient failures only). */
    suspend fun retryFailedPairingConnect() {
        val deviceId = ui.failedPairingDeviceId ?: return
        ui.isBusy = true
        try {
            connection.connect(deviceId, forceReconnect = true)
            ui.failedPairingDeviceId = null
            wireServices()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (ConnectionFailures.isDeviceConnectedToOtherApp(failure)) {
                ui.failedPairingDeviceId = null
                ui.presentPairingFailure(PairingError.DeviceConnectedToOtherApp(deviceId))
            } else {
                ui.presentPairingFailure(
                    PairingError.ConnectionFailed(deviceId, failure, ConnectionFailures.isAuthenticationFailure(failure)),
                )
            }
        } finally {
            ui.isBusy = false
        }
    }

    /** Foreground handling of the pairing-related deferrals (Swift `handleBecameActive` prefix). Returns true when it consumed activation. */
    suspend fun handleBecameActive(): Boolean {
        if (ui.shouldCompleteFreshPairingOnForeground) {
            resumeFreshPairingIfNeeded()
            return true
        }
        if (ui.shouldShowPickerOnForeground && ui.pendingSystemPairingSetup == null && ui.queuedSystemPairingSetup == null) {
            ui.shouldShowPickerOnForeground = false
            startDeviceScan()
        }
        return false
    }
}
