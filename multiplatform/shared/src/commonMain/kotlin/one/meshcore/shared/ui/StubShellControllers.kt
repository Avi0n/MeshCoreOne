package one.meshcore.shared.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Inert platform controllers for hosts without a live connection stack, including the desktop app and
 * unit tests. They satisfy [ShellDependencies] so the shared
 * root, onboarding, and pill render and can be exercised, while the connection commands are no-ops.
 */
class InertPermissionController(initial: PermissionUiState = PermissionUiState()) : PermissionController {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<PermissionUiState> = _state

    /** Marks [permission] granted, so a test or the desktop stub can advance the permission cards. */
    override fun request(permission: OnboardingPermission) {
        _state.value = _state.value.copy(
            granted = _state.value.granted + permission,
            determined = _state.value.determined + permission,
        )
    }

    override fun refresh() = Unit
}

/** A BLE scanner that never finds a device — the desktop stub (desktop has no BLE). */
class InertBleScanController : BleScanController {
    override val availability: StateFlow<BleAvailability> = MutableStateFlow(BleAvailability.UNAUTHORIZED)
    override val devices: StateFlow<List<ScannedDevice>> = MutableStateFlow(emptyList())

    override fun startScan() = Unit

    override fun stopScan() = Unit
}

/** Connection commands that do nothing, for hosts without a live transport. */
class InertConnectionActions(private val onDismissAlert: () -> Unit = {}) : ConnectionActions {
    override suspend fun connect(deviceId: String) = Unit

    override suspend fun connectViaWiFi(host: String, port: Int) = Unit

    override suspend fun connectSimulator() = Unit

    override suspend fun disconnect() = Unit

    override suspend fun sendAdvert(flood: Boolean) = one.meshcore.shared.navigation.SendAdvertShortcutOutcome.Sent

    override fun dismissAlert() = onDismissAlert()
}
