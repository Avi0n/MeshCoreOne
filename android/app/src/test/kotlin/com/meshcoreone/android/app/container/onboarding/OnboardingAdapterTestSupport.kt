// AndroidOnly: WP-303 Fakes shared by the onboarding adapter tests.
package com.meshcoreone.android.app.container.onboarding

import com.meshcoreone.android.app.state.ConnectionUiSnapshot
import com.meshcoreone.android.core.connectivity.permissions.ConnectivityPermission
import com.meshcoreone.android.core.connectivity.permissions.PermissionSnapshot
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.services.device.RadioPreset
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeConnectionHost(
    override val ui: MutableStateFlow<ConnectionUiSnapshot> = MutableStateFlow(ConnectionUiSnapshot()),
) : OnboardingConnectionHost {
    val state = MutableStateFlow(DeviceConnectionState.DISCONNECTED)
    override val currentState: DeviceConnectionState get() = state.value
    override fun connectionStates(): Flow<DeviceConnectionState> = state
    override var isPairingActive = false
    override var registeredAccessoryCount = 0
    override var hasLastConnectedDevice = false
    var onStartPairing: suspend () -> Unit = {}
    var onRetry: suspend (UUID) -> Unit = {}
    var onWiFi: suspend (String, UShort) -> Unit = { _, _ -> }
    var consumed = 0
    var cleared = 0
    var lastWiFi: Pair<String, UShort>? = null

    override suspend fun startPairing(): com.meshcoreone.android.feature.onboarding.OnboardingConnectOutcome {
        onStartPairing()
        return OnboardingConnectMapping.afterPairing(currentState, ui.value)
    }
    override suspend fun retryConnect(deviceId: UUID) = onRetry(deviceId)
    override suspend fun connectWiFi(host: String, port: UShort) { lastWiFi = host to port; onWiFi(host, port) }
    override suspend fun clearStaleRegistrations() { cleared++; registeredAccessoryCount = 0 }
    override fun consumeFailure() { consumed++; ui.value = ui.value.copy(showingConnectionFailedAlert = false, connectionFailedMessage = null, otherAppWarningDeviceId = null) }
}

class FakeRadio(var failure: Exception? = null, var demo: Boolean = false) : OnboardingRadioConfigurator {
    val state = MutableStateFlow(DeviceConnectionState.READY)
    val applied = mutableListOf<RadioPreset>()
    override fun connectionStates(): Flow<DeviceConnectionState> = state
    override val isDemoRadio: Boolean get() = demo
    override suspend fun applyPreset(preset: RadioPreset) { failure?.let { throw it }; applied += preset }
}

class FakePermissionFacts(
    var sdk: Int = 34,
    var granted: Set<ConnectivityPermission> = emptySet(),
    var location: Boolean = false,
    var bluetoothEnabled: Boolean = true,
) : OnboardingPermissionFacts {
    override fun snapshot() = PermissionSnapshot(sdk, granted, bluetoothEnabled = bluetoothEnabled)
    override fun locationGranted() = location
}
