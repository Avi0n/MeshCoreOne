package one.meshcore.shared.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.connection.CompanionAssociation
import one.meshcore.shared.connection.bleSignalTier
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_ok
import one.meshcore.shared.resources.onboarding_device_scan_already_paired
import one.meshcore.shared.resources.onboarding_device_scan_continue
import one.meshcore.shared.resources.onboarding_device_scan_demo_mode_alert_message
import one.meshcore.shared.resources.onboarding_device_scan_demo_mode_alert_title
import one.meshcore.shared.resources.onboarding_device_scanner_unknown_device
import one.meshcore.shared.resources.onboarding_no_device_sheet_body
import one.meshcore.shared.resources.onboarding_no_device_sheet_cancel
import one.meshcore.shared.resources.onboarding_no_device_sheet_confirm
import one.meshcore.shared.resources.onboarding_no_device_sheet_title
import one.meshcore.shared.ui.AcknowledgedAlertDialog
import one.meshcore.shared.ui.BleScanRemedyContent
import one.meshcore.shared.ui.BleScanScanningContent
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.DeviceTransportGlyph
import one.meshcore.shared.ui.OnboardingPermission
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.ScannedDevice
import one.meshcore.shared.ui.ShellDependencies
import one.meshcore.shared.ui.SignalBars
import one.meshcore.shared.ui.WiFiConnectDialog
import one.meshcore.shared.ui.bleScanRemedy
import one.meshcore.shared.ui.rememberAppHaptics
import one.meshcore.shared.ui.scannedDeviceAccessibilityLabel
import one.meshcore.shared.ui.startBleScanRequestingPermission
import org.jetbrains.compose.resources.stringResource

/** Hosts the Pair step's four modal overlays; each shows only when its [visibility] flag is set. */
@Composable
internal fun PairStepDialogs(
    showNoDeviceSheet: Boolean,
    showDemoUnlockedAlert: Boolean,
    showWiFi: Boolean,
    showTroubleshooting: Boolean,
    connecting: Boolean,
    openBluetoothSettings: () -> Unit,
    onExplore: () -> Unit,
    onDismissNoDevice: () -> Unit,
    onDismissDemoAlert: () -> Unit,
    onDismissWiFi: () -> Unit,
    onConnectWiFi: (host: String, port: Int) -> Unit,
    onDismissTroubleshooting: () -> Unit,
    companionAssociations: List<CompanionAssociation>,
    onClearStalePairings: () -> Unit,
) {
    if (showNoDeviceSheet) {
        NoDeviceSheet(onConfirm = onExplore, onDismiss = onDismissNoDevice)
    }
    if (showDemoUnlockedAlert) {
        DemoModeUnlockedAlert(onDismiss = onDismissDemoAlert)
    }
    if (showWiFi) {
        WiFiConnectDialog(connecting = connecting, onDismiss = onDismissWiFi, onConnect = onConnectWiFi)
    }
    if (showTroubleshooting) {
        TroubleshootingDialog(
            onDismiss = onDismissTroubleshooting,
            onOpenBluetoothSettings = openBluetoothSettings,
            associations = companionAssociations,
            onClearStalePairings = onClearStalePairings,
        )
    }
}

@Composable
internal fun AlreadyPairedContent(onContinue: () -> Unit, onBack: () -> Unit) {
    OnboardingScaffold(
        onBack = onBack,
        primary = {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.onboarding_device_scan_continue))
            }
        },
    ) {
        Spacer(Modifier.size(LARGE_SPACING))
        PulsingAntenna()
        StepTitle(stringResource(Res.string.onboarding_device_scan_already_paired))
    }
}

@Composable
internal fun BleDevicePicker(deps: ShellDependencies, connecting: Boolean, onSelect: (String) -> Unit) {
    val bleScan = deps.bleScan
    val permissionController = deps.permissions
    val actions = deps.actions
    val devices by bleScan.devices.collectAsStateWithLifecycle()
    val availability by bleScan.availability.collectAsStateWithLifecycle()
    val permissions by permissionController.state.collectAsStateWithLifecycle()

    // Asking for the grant is part of starting the scan: on API 31+ a scan without BLUETOOTH_SCAN yields
    // nothing, so a first run that never asks leaves the responder pairing with a radio that cannot appear.
    LaunchedEffect(bleScan, permissionController, actions) {
        actions.setPairingInProgress(true)
        startBleScanRequestingPermission(permissionController, bleScan)
    }
    LaunchedEffect(permissions.isGranted(OnboardingPermission.BLUETOOTH), bleScan, permissionController) {
        if (permissions.isGranted(OnboardingPermission.BLUETOOTH)) {
            bleScan.stopScan()
            bleScan.startScan()
        }
    }
    // Stop scanning when the picker leaves composition so the radio scan does not run unbounded.
    DisposableEffect(bleScan, actions) {
        onDispose {
            bleScan.stopScan()
            actions.setPairingInProgress(false)
        }
    }

    // Sort by name then id for a stable list — the macOS DeviceScannerSheet rule that avoids RSSI reshuffle.
    val sorted = devices.sortedWith(compareBy({ it.name ?: "" }, { it.id }))
    val remedy = bleScanRemedy(availability)

    PreferenceHubSection(
        applyHorizontalPadding = false,
    ) {
        when {
            remedy != null -> BleScanRemedyContent(
                remedy = remedy,
                onRequestBluetooth = { deps.permissions.request(OnboardingPermission.BLUETOOTH) },
                onOpenSettings = deps.openAppSettings,
                onOpenBluetoothSettings = deps.openBluetoothSettings,
            )
            sorted.isEmpty() -> BleScanScanningContent()
            else -> sorted.forEach { device -> DeviceRow(device, enabled = !connecting, onSelect = onSelect) }
        }
    }
}

@Composable
private fun DeviceRow(device: ScannedDevice, enabled: Boolean, onSelect: (String) -> Unit) {
    val name = device.name ?: stringResource(Res.string.onboarding_device_scanner_unknown_device)
    val rowLabel = scannedDeviceAccessibilityLabel(name, device.rssi)
    ListItem(
        modifier = Modifier
            .clickable(enabled = enabled) { onSelect(device.id) }
            .semantics { contentDescription = rowLabel },
        leadingContent = { DeviceTransportGlyph(viaWifi = false) },
        headlineContent = {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
        },
        trailingContent = device.rssi?.let { rssi ->
            { SignalBars(bleSignalTier(rssi)) }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Confirmation before exiting onboarding into the empty app without a radio (iOS `NoDeviceSheet`). */
@Composable
private fun NoDeviceSheet(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberAppHaptics()
    ConfirmAlertDialog(
        title = stringResource(Res.string.onboarding_no_device_sheet_title),
        message = stringResource(Res.string.onboarding_no_device_sheet_body),
        confirmLabel = stringResource(Res.string.onboarding_no_device_sheet_confirm),
        dismissLabel = stringResource(Res.string.onboarding_no_device_sheet_cancel),
        onConfirm = {
            haptics.selection()
            onConfirm()
        },
        onDismiss = onDismiss,
    )
}

/** Notice shown after the hidden 3-tap unlocks demo mode (iOS `DeviceScan.DemoModeAlert`). */
@Composable
private fun DemoModeUnlockedAlert(onDismiss: () -> Unit) {
    AcknowledgedAlertDialog(
        title = stringResource(Res.string.onboarding_device_scan_demo_mode_alert_title),
        message = stringResource(Res.string.onboarding_device_scan_demo_mode_alert_message),
        confirmLabel = stringResource(Res.string.localizable_common_ok),
        onDismiss = onDismiss,
    )
}
