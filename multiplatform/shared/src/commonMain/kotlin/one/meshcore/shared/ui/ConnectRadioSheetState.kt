package one.meshcore.shared.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.connection.CompanionAssociation
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.SavedRadioDevice
import one.meshcore.shared.model.Uuid

/** Remembered fields for the connect sheet. Call sites pass [ConnectSheetCall] for live reads. */
internal class ConnectRadioSheetState(initialSawNonReady: Boolean) {
    var showWiFi by mutableStateOf(false)
    var editingWifi by mutableStateOf<SavedRadioDevice?>(null)
    var showTroubleshooting by mutableStateOf(false)
    var showScanSection by mutableStateOf(false)
    var connecting by mutableStateOf(false)
    var startedConnect by mutableStateOf(false)
    var sawNonReady by mutableStateOf(initialSawNonReady)
    var error by mutableStateOf<String?>(null)
    var savedDevices by mutableStateOf<List<SavedRadioDevice>>(emptyList())
    var needsSetup by mutableStateOf<List<CompanionAssociation>>(emptyList())
    var pendingSetup by mutableStateOf<List<CompanionAssociation>?>(null)
    var pendingForget by mutableStateOf<SavedRadioDevice?>(null)
    var elsewhereIds by mutableStateOf<Set<Uuid>>(emptySet())
    var lastConnectedId by mutableStateOf<Uuid?>(null)
}

/**
 * Values the sheet reads at the moment a coroutine runs. [connectionState] stays a getter so a
 * resume sees the latest snapshot instead of the value from when the click happened.
 */
internal class ConnectSheetCall(
    val scope: CoroutineScope,
    private val dependencies: () -> ShellDependencies,
    val transportErrorText: (Throwable) -> String?,
    val settingsErrorText: (Throwable) -> String,
    val dismiss: () -> Unit,
    val connectionState: () -> DeviceConnectionState,
) {
    val deps: ShellDependencies
        get() = dependencies()
}
