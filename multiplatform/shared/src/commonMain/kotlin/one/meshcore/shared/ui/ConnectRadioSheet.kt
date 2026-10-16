package one.meshcore.shared.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.clearStaleCompanionPairings
import one.meshcore.shared.onboarding.TroubleshootingDialog
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_cancel
import one.meshcore.shared.resources.localizable_common_delete
import one.meshcore.shared.resources.settings_device_selection_forget_this_device
import one.meshcore.shared.resources.settings_device_selection_setup_conversations
import one.meshcore.shared.resources.settings_device_selection_title
import one.meshcore.shared.settings.ui.rememberSettingsErrorText
import org.jetbrains.compose.resources.stringResource

/**
 * Post-onboarding radio connect / change-device sheet (iOS `DeviceSelectionSheet`).
 *
 * Shows previously paired radios when present (WiFi always tappable; BLE when advertising or
 * always when only one path exists), plus a live BLE scan list and WiFi/manual entry.
 * With no saved radios, matches iOS `EmptyStateView` (No Paired Devices, Scan for Devices, WiFi)
 * until the user opens the scan list.
 * Selecting a scan row connects to **that** peripheral id (not the first advertisement).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectRadioSheet(deps: ShellDependencies, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val connectionState by deps.connectionState.collectAsStateWithLifecycle()
    val devices by deps.bleScan.devices.collectAsStateWithLifecycle()
    val availability by deps.bleScan.availability.collectAsStateWithLifecycle()
    val sheet = remember { ConnectRadioSheetState(connectionState != DeviceConnectionState.READY) }
    val permissionState by deps.permissions.state.collectAsStateWithLifecycle()
    val sorted = remember(devices) {
        devices.sortedWith(compareBy({ it.name ?: "" }, { it.id }))
    }
    val transportErrorText = rememberTcpTransportErrorText()
    val settingsErrorText = rememberSettingsErrorText()
    val currentDeps by rememberUpdatedState(deps)
    val currentTransportErrorText by rememberUpdatedState(transportErrorText)
    val currentSettingsErrorText by rememberUpdatedState(settingsErrorText)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val bleScan = deps.bleScan
    val companionDevices = deps.companionDevices
    val actions = deps.actions
    val permissions = deps.permissions
    val consumeCompanionPairing = deps.consumeCompanionPairingOnPicker
    val call = remember(scope) {
        ConnectSheetCall(
            scope = scope,
            dependencies = { currentDeps },
            transportErrorText = { error -> currentTransportErrorText(error) },
            settingsErrorText = { error -> currentSettingsErrorText(error) },
            dismiss = { currentDismiss() },
            connectionState = { currentDeps.connectionState.value },
        )
    }

    LaunchedEffect(bleScan, companionDevices, actions) {
        sheet.openConnectSheet(call)
    }
    DisposableEffect(bleScan, companionDevices, actions) {
        onDispose {
            bleScan.stopScan()
            companionDevices.cancelAssociate()
            actions.setPairingInProgress(false)
        }
    }
    LaunchedEffect(connectionState) {
        sheet.onConnectionStateChanged(call)
    }
    LaunchedEffect(sheet.savedDevices, connectionState, sheet.lastConnectedId, companionDevices, actions) {
        sheet.refreshElsewhere(call)
    }
    LaunchedEffect(permissionState.isGranted(OnboardingPermission.BLUETOOTH), bleScan, permissions) {
        sheet.onBluetoothPermissionGranted(
            call,
            permissionState.isGranted(OnboardingPermission.BLUETOOTH),
        )
    }
    LaunchedEffect(consumeCompanionPairing) {
        sheet.consumePendingCompanionPairing(call)
    }

    Scaffold(
        topBar = {
            AppNestedTopBar(
                title = stringResource(Res.string.settings_device_selection_title),
                onClose = { sheet.closeIfIdle(call) },
            )
        },
    ) { innerPadding ->
        ConnectRadioBody(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = SHEET_H_PADDING, vertical = SHEET_V_PADDING),
            savedDevices = sheet.savedDevices,
            needsSetup = sheet.needsSetup,
            elsewhereIds = sheet.elsewhereIds,
            connecting = sheet.connecting,
            showScanSection = sheet.showScanSection,
            error = sheet.error.takeIf { !sheet.showWiFi },
            availability = availability,
            sortedScan = sorted,
            onSavedSelect = { device -> sheet.selectSaved(call, device) },
            onSavedDelete = { device -> sheet.pendingForget = device },
            onSavedEdit = { device ->
                sheet.editingWifi = device
                sheet.error = null
                sheet.showWiFi = true
            },
            onScanSelect = { handle ->
                sheet.beginConnect(call, dismissOnFailure = true) { deps.actions.connect(handle) }
            },
            onRequestBluetooth = { deps.permissions.request(OnboardingPermission.BLUETOOTH) },
            onOpenSettings = deps.openAppSettings,
            onOpenBluetoothSettings = deps.openBluetoothSettings,
            onShowWiFi = {
                sheet.editingWifi = null
                sheet.error = null
                sheet.showWiFi = true
            },
            onShowScan = { sheet.beginCompanionPairing(call) },
            onSetup = { accessory -> sheet.pendingSetup = listOf(accessory) },
            onShowHelp = { sheet.showTroubleshooting = true },
        )
    }

    if (sheet.showWiFi) {
        val wifiDraft = sheet.editingWifi?.let(::savedWifiEditDraft)
        WiFiConnectDialog(
            initialHost = wifiDraft?.first.orEmpty(),
            initialPort = wifiDraft?.second?.toString() ?: DEFAULT_WIFI_PORT,
            isEditing = wifiDraft != null,
            error = sheet.error,
            onDismiss = { sheet.dismissWifiEditorIfIdle() },
            connecting = sheet.connecting,
            onConnect = { host, port ->
                sheet.beginConnect(call) {
                    deps.actions.connectViaWiFi(host, port)
                    sheet.showWiFi = false
                    sheet.editingWifi = null
                }
            },
        )
    }
    if (sheet.showTroubleshooting) {
        TroubleshootingDialog(
            onDismiss = { sheet.showTroubleshooting = false },
            onOpenBluetoothSettings = deps.openBluetoothSettings,
            associations = deps.companionDevices.associations(),
            onClearStalePairings = {
                clearStaleCompanionPairings(deps.companionDevices.associations()) { handle ->
                    deps.companionDevices.disassociate(handle)
                }
                sheet.showTroubleshooting = false
            },
        )
    }
    sheet.pendingSetup?.let { accessories ->
        SystemPairingSetupDialog(
            accessories = accessories,
            onForget = { sheet.confirmLeftoverForget(call) },
            onCancel = { sheet.pendingSetup = null },
        )
    }
    sheet.pendingForget?.let { device ->
        ConfirmAlertDialog(
            title = stringResource(Res.string.settings_device_selection_forget_this_device),
            message = stringResource(Res.string.settings_device_selection_setup_conversations),
            confirmLabel = stringResource(Res.string.localizable_common_delete),
            dismissLabel = stringResource(Res.string.localizable_common_cancel),
            onConfirm = { sheet.forgetSavedDevice(call, device) },
            onDismiss = { sheet.pendingForget = null },
            confirmButtonColors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        )
    }
}

private val SHEET_H_PADDING = 16.dp
private val SHEET_V_PADDING = 8.dp
