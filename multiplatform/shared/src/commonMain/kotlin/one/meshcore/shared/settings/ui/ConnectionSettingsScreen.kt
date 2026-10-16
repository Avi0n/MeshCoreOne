package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.connection.ConnectionSettingsSnapshot
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.TransportType
import one.meshcore.shared.connection.showsWifiConnectionSettings
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_settings_connection_unrestricted_battery_accessibility_hint
import one.meshcore.shared.resources.android_settings_connection_unrestricted_battery_footer
import one.meshcore.shared.resources.android_settings_connection_unrestricted_battery_title
import one.meshcore.shared.resources.settings_device_connected
import one.meshcore.shared.resources.settings_device_info_connection_header
import one.meshcore.shared.resources.settings_device_info_connection_status
import one.meshcore.shared.resources.settings_device_selection_bluetooth
import one.meshcore.shared.resources.settings_wifi_header
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubEdgeFill
import one.meshcore.shared.ui.preferenceHubEdgeScroll
import org.jetbrains.compose.resources.stringResource

/** Trailing glyph for the Unrestricted battery settings deep link. */
internal val CONNECTION_BATTERY_OPTIMIZATION_ICON get() = AppIcons.Link

/** Empty Wi‑Fi host/port (and similar preference fields) show localized Not set, not an em dash. */
internal fun connectionUnsetPreferenceText(value: String?, notSet: String): String = value ?: notSet

internal fun connectionSnapshotAfterBlePinSaved(
    snapshot: ConnectionSettingsSnapshot?,
    pin: Long,
): ConnectionSettingsSnapshot? = snapshot?.copy(blePin = pin)

internal fun connectionSnapshotAfterWifiSaved(
    snapshot: ConnectionSettingsSnapshot?,
    host: String,
    port: Int,
): ConnectionSettingsSnapshot? = snapshot?.copy(wifiHost = host, wifiPort = port)

/**
 * Settings → Connection: transport status, BLE PIN edit (live radio), WiFi host/port edit,
 * and (Android) Unrestricted battery so Doze cannot kill the connection service.
 * Setting a custom PIN, changing it, or reverting to the firmware default all write the live radio
 * and reboot it to commit; WiFi edits reconnect at the new address. WiFi vs Bluetooth is exclusive
 * on the live transport, matching iOS `ConnectionSettingsView`.
 */
@Composable
fun ConnectionSettingsScreen(
    snapshot: ConnectionSettingsSnapshot?,
    connectionState: StateFlow<DeviceConnectionState>,
    modifier: Modifier = Modifier,
    onSaveBlePin: (suspend (Long) -> Unit)? = null,
    onSaveWifi: (suspend (host: String, port: Int) -> Unit)? = null,
    onOpenBatteryOptimization: (() -> Unit)? = null,
) {
    var displayedSnapshot by remember(snapshot) { mutableStateOf(snapshot) }
    // The snapshot is built from the last-connected device and survives a disconnect so PIN type stays
    // readable offline. WiFi vs Bluetooth follows the live transport (iOS `isWiFi`).
    val liveState by connectionState.collectAsStateWithLifecycle()
    val isConnected = liveState == DeviceConnectionState.READY
    val showWifi = showsWifiConnectionSettings(displayedSnapshot?.transportType)
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val retryAlert = rememberRetryAlertState()
    val currentSnapshot = displayedSnapshot

    if (currentSnapshot == null) {
        Column(modifier.preferenceHubEdgeFill()) {
            UnrestrictedBatteryRow(onOpenBatteryOptimization)
            SettingsNoDeviceEmpty(Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Column(modifier.preferenceHubEdgeScroll()) {
            UnrestrictedBatteryRow(onOpenBatteryOptimization)

            if (isConnected) {
                PreferenceHubSection(
                    title = stringResource(Res.string.settings_device_info_connection_header),
                    applyHorizontalPadding = false,
                ) {
                    PreferenceHubValueRow(
                        stringResource(Res.string.settings_device_info_connection_status),
                        stringResource(Res.string.settings_device_connected),
                    )
                    val liveTransport = when (currentSnapshot.transportType) {
                        TransportType.BLUETOOTH -> stringResource(Res.string.settings_device_selection_bluetooth)
                        TransportType.WIFI -> stringResource(Res.string.settings_wifi_header)
                        null -> null
                    }
                    if (liveTransport != null) {
                        PreferenceHubValueRow(
                            stringResource(Res.string.settings_device_info_connection_header),
                            liveTransport,
                        )
                    }
                }
            }

            if (showWifi) {
                ConnectionWifiSection(
                    snapshot = currentSnapshot,
                    onSaveWifi = onSaveWifi,
                    onSnapshotChange = { displayedSnapshot = it },
                )
            } else {
                BluetoothPinSection(
                    snapshot = currentSnapshot,
                    canWrite = onSaveBlePin != null && currentSnapshot.transportType != null,
                    isRadioReady = isConnected,
                    isSaving = isSaving,
                    onSaveBlePin = onSaveBlePin,
                    onSavingChange = { isSaving = it },
                    onError = { errorMessage = it },
                    onPinSaved = { pin ->
                        displayedSnapshot = connectionSnapshotAfterBlePinSaved(displayedSnapshot, pin)
                    },
                    retryAlert = retryAlert,
                )
            }
        }
    }

    SettingsWriteFeedback(retryAlert, errorMessage) { errorMessage = null }
}

@Composable
private fun UnrestrictedBatteryRow(onOpenBatteryOptimization: (() -> Unit)?) {
    val open = onOpenBatteryOptimization ?: return
    PreferenceHubSection(applyHorizontalPadding = false) {
        ListItem(
            headlineContent = {
                Text(stringResource(Res.string.android_settings_connection_unrestricted_battery_title))
            },
            trailingContent = {
                Icon(
                    imageVector = CONNECTION_BATTERY_OPTIMIZATION_ICON,
                    contentDescription = stringResource(
                        Res.string.android_settings_connection_unrestricted_battery_accessibility_hint,
                    ),
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth().clickable(onClick = open),
        )
    }
    PreferenceHubFooter(
        stringResource(Res.string.android_settings_connection_unrestricted_battery_footer),
    )
}
