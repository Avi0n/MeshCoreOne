package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_ble_scan_allow
import one.meshcore.shared.resources.onboarding_device_scanner_bluetooth_off_message
import one.meshcore.shared.resources.onboarding_device_scanner_bluetooth_off_title
import one.meshcore.shared.resources.onboarding_device_scanner_bluetooth_unauthorized_message
import one.meshcore.shared.resources.onboarding_device_scanner_bluetooth_unauthorized_title
import one.meshcore.shared.resources.onboarding_device_scanner_scanning
import one.meshcore.shared.resources.onboarding_permissions_location_alert_open_settings
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * What an unusable BLE adapter needs from the user before any scan can find a radio. Kept separate from
 * the composable so both scan pickers — the onboarding Pair step and the post-onboarding connect sheet —
 * branch on one classification instead of each inventing its own.
 */
internal enum class BleScanRemedy {
    /** The adapter is off; only the user can turn it back on. */
    POWER_ON_BLUETOOTH,

    /** The scan permission is missing or refused; offer the prompt and the app-settings deep link. */
    GRANT_BLUETOOTH_PERMISSION,
}

/** The remedy [availability] needs, or `null` when a scan can proceed. */
internal fun bleScanRemedy(availability: BleAvailability): BleScanRemedy? = when (availability) {
    BleAvailability.READY -> null
    BleAvailability.POWERED_OFF -> BleScanRemedy.POWER_ON_BLUETOOTH
    BleAvailability.UNAUTHORIZED -> BleScanRemedy.GRANT_BLUETOOTH_PERMISSION
}

/**
 * The named problem plus the actions that can fix it. Without this a scan that can never return a device
 * renders as an endless spinner, which reads as "no radio in range" and leaves the user no way forward.
 */
@Composable
internal fun BleScanRemedyContent(
    remedy: BleScanRemedy,
    onRequestBluetooth: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit = onOpenSettings,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(PreferenceHubCardPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val title = when (remedy) {
            BleScanRemedy.POWER_ON_BLUETOOTH -> Res.string.onboarding_device_scanner_bluetooth_off_title
            BleScanRemedy.GRANT_BLUETOOTH_PERMISSION ->
                Res.string.onboarding_device_scanner_bluetooth_unauthorized_title
        }
        val message = when (remedy) {
            BleScanRemedy.POWER_ON_BLUETOOTH -> Res.string.onboarding_device_scanner_bluetooth_off_message
            BleScanRemedy.GRANT_BLUETOOTH_PERMISSION ->
                Res.string.onboarding_device_scanner_bluetooth_unauthorized_message
        }
        Icon(
            imageVector = AppIcons.CellTowerOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
        )
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (remedy == BleScanRemedy.POWER_ON_BLUETOOTH) {
            Button(
                onClick = onOpenBluetoothSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.onboarding_permissions_location_alert_open_settings))
            }
        }
        if (remedy == BleScanRemedy.GRANT_BLUETOOTH_PERMISSION) {
            Button(
                onClick = onRequestBluetooth,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.android_ble_scan_allow))
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.onboarding_permissions_location_alert_open_settings))
            }
        }
    }
}

/** iOS `DeviceScannerSheet` scanning: `antenna.radiowaves.left.and.right`. */
@Composable
internal fun BleScanScanningContent() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(PreferenceHubCardPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = AppIcons.CellTower,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
        )
        Text(
            text = stringResource(Res.string.onboarding_device_scanner_scanning),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        CircularProgressIndicator(modifier = Modifier.size(BLE_SCAN_SCANNING_SPINNER_SIZE))
    }
}

private val BLE_SCAN_SCANNING_SPINNER_SIZE = 20.dp

/**
 * Asks for the Bluetooth grant when it is missing, then starts the scan. On API 31+ a scan without
 * `BLUETOOTH_SCAN` throws and yields nothing, so a picker that scans without asking first can never
 * surface a radio — first-run pairing then fails silently.
 */
internal fun startBleScanRequestingPermission(permissions: PermissionController, bleScan: BleScanController) {
    if (!permissions.state.value.isGranted(OnboardingPermission.BLUETOOTH)) {
        permissions.request(OnboardingPermission.BLUETOOTH)
    }
    bleScan.startScan()
}

/**
 * Stops the in-app NUS scan before a connect. iOS `pairNewDevice` calls `stopBLEScanning` before
 * discover/connect so GATT and the scan do not run together. Returns false when a connect is
 * already in flight so a second tap cannot start another ceremony.
 */
internal fun prepareScannedDeviceConnect(connecting: Boolean, stopScan: () -> Unit): Boolean {
    if (connecting) return false
    stopScan()
    return true
}
