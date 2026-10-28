// AndroidOnly: WP-303 Onboarding pairing, Wi-Fi and demo ports over app state and the pairing coordinator.
package com.meshcoreone.android.app.container.onboarding

import com.meshcoreone.android.app.state.ConnectionFailures
import com.meshcoreone.android.app.state.ConnectionUiSnapshot
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.services.simulator.DemoModeManager
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.onboarding.OnboardingConnectOutcome
import com.meshcoreone.android.feature.onboarding.OnboardingConnectionSnapshot
import com.meshcoreone.android.feature.onboarding.OnboardingDemoPort
import com.meshcoreone.android.feature.onboarding.OnboardingPairingPort
import com.meshcoreone.android.feature.onboarding.OnboardingScanFallbackPort
import com.meshcoreone.android.feature.onboarding.OnboardingWiFiPort
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the onboarding ports need from app state and the runtime; the production binding is [AppStateOnboardingHost]. */
interface OnboardingConnectionHost {
    val currentState: DeviceConnectionState
    fun connectionStates(): Flow<DeviceConnectionState>
    val ui: StateFlow<ConnectionUiSnapshot>
    val isPairingActive: Boolean
    val registeredAccessoryCount: Int
    val hasLastConnectedDevice: Boolean

    /** Runs the guided pairing flow to completion (picker, bond, connect, service wiring); failures land in [ui]. */
    suspend fun startPairing(): OnboardingConnectOutcome

    /** Reconnects a known device, forcing past a stale link; throws the runtime failure. */
    suspend fun retryConnect(deviceId: UUID)
    suspend fun connectWiFi(host: String, port: UShort)
    suspend fun clearStaleRegistrations()

    /** Clears the app-wide failure alert state once onboarding has taken ownership of the failure. */
    fun consumeFailure()
}

object OnboardingConnectMapping {
    /** Outcome of a finished fresh-pairing run, from the post-run connection and failure state. */
    fun afterPairing(
        state: DeviceConnectionState,
        ui: ConnectionUiSnapshot,
        completed: Boolean = true,
    ): OnboardingConnectOutcome = when {
        ui.otherAppWarningDeviceId != null -> OnboardingConnectOutcome.Failed(
            UiText.Resource(AppLocalizableStrings.alertCouldNotConnectOtherAppMessage), ui.otherAppWarningDeviceId,
        )
        ui.showingConnectionFailedAlert -> OnboardingConnectOutcome.Failed(
            ui.connectionFailedMessage ?: UiText.Resource(AppLocalizableStrings.errorBleDeviceConnectedToOtherApp),
        )
        completed && state.isConnected -> OnboardingConnectOutcome.Connected
        else -> OnboardingConnectOutcome.Cancelled
    }

    fun afterFailure(failure: Throwable, deviceId: UUID?): OnboardingConnectOutcome.Failed =
        if (ConnectionFailures.isDeviceConnectedToOtherApp(failure)) OnboardingConnectOutcome.Failed(
            UiText.Resource(AppLocalizableStrings.alertCouldNotConnectOtherAppMessage), deviceId,
        ) else OnboardingConnectOutcome.Failed(UiText.Verbatim(failure.message ?: failure.javaClass.simpleName))
}

class AppOnboardingPairingPort(
    private val host: OnboardingConnectionHost,
    scope: CoroutineScope,
    override val scanFallback: OnboardingScanFallbackPort?,
) : OnboardingPairingPort {
    private val registered = MutableStateFlow(host.registeredAccessoryCount)

    override val connection: StateFlow<OnboardingConnectionSnapshot> =
        combine(host.connectionStates(), host.ui) { state, ui -> snapshotOf(state, ui) }
            .stateIn(scope, SharingStarted.Eagerly, snapshotOf(host.currentState, host.ui.value))
    override val pairedAccessoryCount: StateFlow<Int> = registered.asStateFlow()
    override val hasLastConnectedDevice: Boolean get() = host.hasLastConnectedDevice

    private fun snapshotOf(state: DeviceConnectionState, ui: ConnectionUiSnapshot) =
        OnboardingConnectionSnapshot(isReady = state == DeviceConnectionState.READY, isBusy = ui.isBusy, isDemoDevice = false)

    override suspend fun pairNewDevice(): OnboardingConnectOutcome {
        if (host.isPairingActive) return OnboardingConnectOutcome.Ignored
        host.consumeFailure()
        val outcome = host.startPairing()
        registered.value = host.registeredAccessoryCount
        if (outcome is OnboardingConnectOutcome.Failed) host.consumeFailure()
        return outcome
    }

    override suspend fun retryConnection(deviceId: UUID): OnboardingConnectOutcome {
        host.consumeFailure()
        return try {
            host.retryConnect(deviceId)
            OnboardingConnectOutcome.Connected
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            OnboardingConnectMapping.afterFailure(failure, deviceId)
        }
    }

    override suspend fun clearStalePairings() {
        host.clearStaleRegistrations()
        registered.value = host.registeredAccessoryCount
    }
}

class AppOnboardingWiFiPort(private val host: OnboardingConnectionHost) : OnboardingWiFiPort {
    override suspend fun connect(host: String, port: Int): OnboardingConnectOutcome {
        if (port !in MIN_PORT..MAX_PORT) {
            return OnboardingConnectOutcome.Failed(UiText.Verbatim("Port must be between $MIN_PORT and $MAX_PORT."))
        }
        return try {
            this.host.connectWiFi(host, port.toUShort())
            OnboardingConnectOutcome.Connected
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            OnboardingConnectMapping.afterFailure(failure, null)
        }
    }

    private companion object { const val MIN_PORT = 1; const val MAX_PORT = 65_535 }
}

/**
 * Demo mode flags from [DemoModeManager]. The simulator connection ([simulatorConnect]) is not bound in the runtime
 * yet (WP-217 deviation), so without it [connectDemo] reports the feature as unavailable instead of faking a link.
 */
class AppOnboardingDemoPort(
    private val manager: DemoModeManager,
    scope: CoroutineScope,
    private val simulatorConnect: (suspend () -> Unit)? = null,
) : OnboardingDemoPort {
    override val isEnabled: StateFlow<Boolean> =
        manager.state.map { it.isEnabled }.stateIn(scope, SharingStarted.Eagerly, manager.isEnabled)

    override fun unlock() = manager.unlock()

    override suspend fun connectDemo(): OnboardingConnectOutcome {
        val connect = simulatorConnect
            ?: return OnboardingConnectOutcome.Failed(UiText.Resource(AppLocalizableStrings.errorMeshCoreFeatureDisabled))
        return try {
            connect()
            OnboardingConnectOutcome.Connected
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            OnboardingConnectOutcome.Failed(UiText.Verbatim(failure.message ?: failure.javaClass.simpleName))
        }
    }
}
