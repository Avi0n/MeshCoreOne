package one.meshcore.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.meshcore.shared.connection.CompanionAssociation
import one.meshcore.shared.connection.SavedRadioDevice
import one.meshcore.shared.connection.bleScanRssiForHandle
import one.meshcore.shared.connection.bleSignalTier
import one.meshcore.shared.connection.wifiShortDescription
import one.meshcore.shared.connection.wordResource
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_settings_device_selection_accessibility_setup_label
import one.meshcore.shared.resources.localizable_common_delete
import one.meshcore.shared.resources.localizable_common_edit
import one.meshcore.shared.resources.onboarding_device_scanner_title
import one.meshcore.shared.resources.onboarding_device_scanner_unknown_device
import one.meshcore.shared.resources.settings_device_selection_accessibility_connected_elsewhere_hint
import one.meshcore.shared.resources.settings_device_selection_accessibility_connected_elsewhere_label
import one.meshcore.shared.resources.settings_device_selection_accessibility_device_label
import one.meshcore.shared.resources.settings_device_selection_accessibility_out_of_range_hint
import one.meshcore.shared.resources.settings_device_selection_accessibility_select_action
import one.meshcore.shared.resources.settings_device_selection_accessibility_setup_hint
import one.meshcore.shared.resources.settings_device_selection_bluetooth
import one.meshcore.shared.resources.settings_device_selection_connected_elsewhere
import one.meshcore.shared.resources.settings_device_selection_setup
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SavedDeviceList(
    devices: List<SavedRadioDevice>,
    elsewhereIds: Set<Uuid>,
    connecting: Boolean,
    signalByHandle: Map<String, Int>,
    onSelect: (SavedRadioDevice) -> Unit,
    onDelete: (SavedRadioDevice) -> Unit,
    onEdit: (SavedRadioDevice) -> Unit,
) {
    val bleLabel = stringResource(Res.string.settings_device_selection_bluetooth)
    val deleteLabel = stringResource(Res.string.localizable_common_delete)
    val editLabel = stringResource(Res.string.localizable_common_edit)
    // TalkBack reads an action label as "double-tap to <label>", so this slot takes the bare verb;
    // the iOS hint sentence would read back doubled.
    val selectAction = stringResource(Res.string.settings_device_selection_accessibility_select_action)
    val labelTemplate = stringResource(Res.string.settings_device_selection_accessibility_device_label)
    val elsewhereLabel = stringResource(Res.string.settings_device_selection_connected_elsewhere)
    val elsewhereSpoken = stringResource(Res.string.settings_device_selection_accessibility_connected_elsewhere_label)
    val elsewhereHint = stringResource(Res.string.settings_device_selection_accessibility_connected_elsewhere_hint)
    val outOfRange = stringResource(Res.string.settings_device_selection_accessibility_out_of_range_hint)
    val warningTint = MaterialTheme.colorScheme.tertiary
    devices.forEach { device ->
        val methodLabel = device.wifi?.let { wifiShortDescription(it) } ?: bleLabel
        // Several paired radios look alike in a list; the live reading is what says which one is here.
        val rssi = bleScanRssiForHandle(device.bleHandle, signalByHandle)
        val unreachable = savedBleDeviceIsUnreachable(device.connectsViaWiFi, rssi)
        // WiFi always tappable; a BLE row with no live advert is dimmed and not selectable,
        // matching iOS `DeviceRow` / `isDisabledByBLE`.
        val enabled = savedBleRowIsEnabled(
            connecting = connecting,
            isConnectable = device.isConnectable,
            unreachable = unreachable,
        )
        val elsewhere = device.id in elsewhereIds
        val tier = rssi?.let { bleSignalTier(it) }
        val supporting = methodLabel
        val speech = savedDeviceSpeech(
            SavedDeviceSpeechInput(
                elsewhere = elsewhere,
                unreachable = unreachable,
                deviceName = device.name,
                methodLabel = methodLabel,
                elsewhereSpoken = elsewhereSpoken,
                outOfRange = outOfRange,
                labelTemplate = labelTemplate,
                tierWord = tier?.let { stringResource(it.wordResource()) },
            ),
        )
        ListItem(
            leadingContent = { DeviceTransportGlyph(viaWifi = device.connectsViaWiFi) },
            headlineContent = {
                Text(
                    device.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                if (elsewhere) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = AppIcons.Warning,
                            contentDescription = null,
                            tint = warningTint,
                            modifier = Modifier.size(ELSEWHERE_WARNING_ICON),
                        )
                        Text(
                            text = elsewhereLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = warningTint,
                            modifier = Modifier.padding(start = ELSEWHERE_WARNING_GAP),
                        )
                    }
                } else {
                    Text(supporting)
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tier != null) SignalBars(tier)
                    if (savedWifiEditDraft(device) != null) {
                        AppIconButton(
                            imageVector = AppIcons.Edit,
                            contentDescription = editLabel,
                            onClick = { onEdit(device) },
                            enabled = !connecting,
                        )
                    }
                    AppIconButton(
                        imageVector = AppIcons.Delete,
                        contentDescription = deleteLabel,
                        onClick = { onDelete(device) },
                        enabled = !connecting,
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MIN_HIT_TARGET)
                .alpha(if (unreachable || elsewhere) UNREACHABLE_SAVED_ROW_ALPHA else 1f)
                .clickable(enabled = enabled, onClickLabel = selectAction) { onSelect(device) }
                .semantics {
                    contentDescription = speech.label
                    savedDeviceElsewhereHint(elsewhere, elsewhereHint)?.let { stateDescription = it }
                },
        )
    }
}

@Composable
internal fun NeedsSetupDeviceList(
    accessories: List<CompanionAssociation>,
    connecting: Boolean,
    onSetup: (CompanionAssociation) -> Unit,
) {
    val setupTitle = stringResource(Res.string.settings_device_selection_setup)
    val setupHint = stringResource(Res.string.settings_device_selection_accessibility_setup_hint)
    val labelTemplate = stringResource(Res.string.android_settings_device_selection_accessibility_setup_label)
    accessories.forEach { accessory ->
        val spoken = formatTemplate(labelTemplate, listOf(accessory.name))
        ListItem(
            leadingContent = { DeviceTransportGlyph(viaWifi = false) },
            headlineContent = {
                Text(
                    accessory.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                FilledTonalButton(
                    onClick = { onSetup(accessory) },
                    enabled = !connecting,
                ) {
                    Text(setupTitle)
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MIN_HIT_TARGET)
                .clickable(enabled = !connecting, onClickLabel = setupTitle) { onSetup(accessory) }
                .semantics {
                    contentDescription = spoken
                    stateDescription = setupHint
                },
        )
    }
}

@Composable
internal fun ScanStatusBody(
    availability: BleAvailability,
    connecting: Boolean,
    devices: List<ScannedDevice>,
    onRequestBluetooth: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onSelect: (String) -> Unit,
) {
    if (connecting) return
    PreferenceHubSection(
        title = stringResource(Res.string.onboarding_device_scanner_title),
        applyHorizontalPadding = false,
    ) {
        val remedy = bleScanRemedy(availability)
        if (remedy == null) {
            ReadyScanList(devices = devices, onSelect = onSelect)
        } else {
            BleScanRemedyContent(
                remedy = remedy,
                onRequestBluetooth = onRequestBluetooth,
                onOpenSettings = onOpenSettings,
                onOpenBluetoothSettings = onOpenBluetoothSettings,
            )
        }
    }
}

@Composable
private fun ReadyScanList(devices: List<ScannedDevice>, onSelect: (String) -> Unit) {
    if (devices.isEmpty()) {
        BleScanScanningContent()
    } else {
        devices.forEach { device -> DeviceListRow(device = device, onSelect = onSelect) }
    }
}

@Composable
internal fun StatusSpinner(label: String) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            CircularProgressIndicator(
                modifier = Modifier.size(SPINNER_SIZE),
                strokeWidth = 2.dp,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun DeviceListRow(device: ScannedDevice, onSelect: (String) -> Unit) {
    val unknown = stringResource(Res.string.onboarding_device_scanner_unknown_device)
    val name = device.name ?: unknown
    val rowLabel = scannedDeviceAccessibilityLabel(name, device.rssi)
    val selectAction = stringResource(Res.string.settings_device_selection_accessibility_select_action)
    ListItem(
        leadingContent = { DeviceTransportGlyph(viaWifi = false) },
        headlineContent = { Text(name) },
        trailingContent = device.rssi?.let { rssi -> { SignalBars(bleSignalTier(rssi)) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_HIT_TARGET)
            .clickable(onClickLabel = selectAction) { onSelect(device.id) }
            .semantics { contentDescription = rowLabel },
    )
}

private val SPINNER_SIZE = 20.dp
private val MIN_HIT_TARGET = 48.dp
private const val UNREACHABLE_SAVED_ROW_ALPHA = 0.4f
private val ELSEWHERE_WARNING_ICON = 14.dp
private val ELSEWHERE_WARNING_GAP = 4.dp
