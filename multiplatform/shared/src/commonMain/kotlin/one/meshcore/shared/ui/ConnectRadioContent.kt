package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.meshcore.shared.connection.CompanionAssociation
import one.meshcore.shared.connection.SavedRadioDevice
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.onboarding_device_scan_connecting
import one.meshcore.shared.resources.onboarding_device_scan_device_not_appearing
import one.meshcore.shared.resources.onboarding_device_scan_subtitle
import one.meshcore.shared.resources.settings_device_selection_connect_via_wifi
import one.meshcore.shared.resources.settings_device_selection_no_paired_description
import one.meshcore.shared.resources.settings_device_selection_no_paired_devices
import one.meshcore.shared.resources.settings_device_selection_previously_paired
import one.meshcore.shared.resources.settings_device_selection_scan_bluetooth
import one.meshcore.shared.resources.settings_device_selection_scan_for_devices
import one.meshcore.shared.resources.settings_device_selection_select_to_reconnect
import one.meshcore.shared.resources.settings_device_selection_setup_footer
import one.meshcore.shared.resources.settings_device_selection_setup_footer_plural
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ConnectRadioBody(
    modifier: Modifier,
    savedDevices: List<SavedRadioDevice>,
    needsSetup: List<CompanionAssociation>,
    elsewhereIds: Set<Uuid>,
    connecting: Boolean,
    showScanSection: Boolean,
    error: String?,
    availability: BleAvailability,
    sortedScan: List<ScannedDevice>,
    onSavedSelect: (SavedRadioDevice) -> Unit,
    onSavedDelete: (SavedRadioDevice) -> Unit,
    onSavedEdit: (SavedRadioDevice) -> Unit,
    onScanSelect: (String) -> Unit,
    onRequestBluetooth: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onShowWiFi: () -> Unit,
    onShowScan: () -> Unit,
    onSetup: (CompanionAssociation) -> Unit,
    onShowHelp: () -> Unit,
) {
    val showingEmpty = shouldShowConnectRadioEmptyState(
        savedCount = savedDevices.size,
        showScanSection = showScanSection,
        needsSetupCount = needsSetup.size,
    )
    Column(modifier = modifier.fillMaxSize()) {
        if (showingEmpty) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Icon(
                    imageVector = CONNECT_RADIO_EMPTY_ICON,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
                )
                Text(
                    text = stringResource(Res.string.settings_device_selection_no_paired_devices),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(Res.string.settings_device_selection_no_paired_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Column(
                modifier = preferenceHubFormScroll(),
                verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
            ) {
                Text(
                    text = stringResource(
                        if (savedDevices.isEmpty()) {
                            Res.string.onboarding_device_scan_subtitle
                        } else {
                            Res.string.settings_device_selection_select_to_reconnect
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (savedDevices.isNotEmpty() || needsSetup.isNotEmpty()) {
                    PreferenceHubSection(
                        title = stringResource(Res.string.settings_device_selection_previously_paired),
                        applyHorizontalPadding = false,
                    ) {
                        SavedDeviceList(
                            devices = savedDevices,
                            elsewhereIds = elsewhereIds,
                            connecting = connecting,
                            signalByHandle = sortedScan.mapNotNull { scan -> scan.rssi?.let { scan.id to it } }.toMap(),
                            onSelect = onSavedSelect,
                            onDelete = onSavedDelete,
                            onEdit = onSavedEdit,
                        )
                        NeedsSetupDeviceList(
                            accessories = needsSetup,
                            connecting = connecting,
                            onSetup = onSetup,
                        )
                    }
                    if (needsSetup.isNotEmpty()) {
                        PreferenceHubFooter(
                            text = stringResource(
                                if (needsSetup.size == 1) {
                                    Res.string.settings_device_selection_setup_footer
                                } else {
                                    Res.string.settings_device_selection_setup_footer_plural
                                },
                            ),
                            applyHorizontalPadding = false,
                        )
                    }
                }

                if (connecting) {
                    StatusSpinner(stringResource(Res.string.onboarding_device_scan_connecting))
                } else if (showScanSection) {
                    ScanStatusBody(
                        availability = availability,
                        connecting = connecting,
                        devices = sortedScan,
                        onRequestBluetooth = onRequestBluetooth,
                        onOpenSettings = onOpenSettings,
                        onOpenBluetoothSettings = onOpenBluetoothSettings,
                        onSelect = onScanSelect,
                    )
                }

                error?.let { FormErrorRow(it) }
            }
        }

        if (!showScanSection) {
            ConnectRadioActionButton(
                text = stringResource(
                    if (savedDevices.isEmpty()) {
                        Res.string.settings_device_selection_scan_for_devices
                    } else {
                        Res.string.settings_device_selection_scan_bluetooth
                    },
                ),
                icon = CONNECT_RADIO_SCAN_ICON,
                enabled = !connecting,
                onClick = onShowScan,
            )
        }
        ConnectRadioActionButton(
            text = stringResource(Res.string.settings_device_selection_connect_via_wifi),
            icon = CONNECT_RADIO_WIFI_ICON,
            enabled = !connecting,
            filled = false,
            onClick = onShowWiFi,
        )
        TextButton(onClick = onShowHelp, enabled = !connecting, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.onboarding_device_scan_device_not_appearing))
        }
    }
}

/** iOS `DeviceSelectionSheet` empty: `antenna.radiowaves.left.and.right.slash`. */
internal val CONNECT_RADIO_EMPTY_ICON get() = AppIcons.CellTowerOff

/** iOS `DeviceSelectionSheet` Connect via Wi‑Fi `wifi.circle`. */
internal val CONNECT_RADIO_WIFI_ICON get() = AppIcons.Wifi

/** iOS `DeviceSelectionSheet` Scan `antenna.radiowaves.left.and.right`. */
internal val CONNECT_RADIO_SCAN_ICON get() = AppIcons.CellTower

@Composable
private fun ConnectRadioActionButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    filled: Boolean = true,
) {
    val content: @Composable () -> Unit = {
        ButtonIconLabel(text, icon)
    }
    if (filled) {
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), content = { content() })
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), content = {
            content()
        })
    }
}

private val SECTION_GAP = 12.dp
