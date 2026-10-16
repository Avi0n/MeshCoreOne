package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.ui.LocationPickerScreen
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_cancel
import one.meshcore.shared.resources.onboarding_permissions_location_alert_message
import one.meshcore.shared.resources.onboarding_permissions_location_alert_open_settings
import one.meshcore.shared.resources.onboarding_permissions_location_alert_title
import one.meshcore.shared.resources.settings_location_auto_update
import one.meshcore.shared.resources.settings_location_device_gps_footer
import one.meshcore.shared.resources.settings_location_device_gps_header
import one.meshcore.shared.resources.settings_location_device_gps_toggle
import one.meshcore.shared.resources.settings_location_footer
import one.meshcore.shared.resources.settings_location_gps_source
import one.meshcore.shared.resources.settings_location_gps_source_device
import one.meshcore.shared.resources.settings_location_gps_source_phone
import one.meshcore.shared.resources.settings_location_picker_latitude
import one.meshcore.shared.resources.settings_location_picker_longitude
import one.meshcore.shared.resources.settings_node_location_not_set
import one.meshcore.shared.resources.settings_node_set_location
import one.meshcore.shared.resources.settings_node_share_location_publicly
import one.meshcore.shared.settings.DeviceGpsState
import one.meshcore.shared.settings.GpsSource
import one.meshcore.shared.settings.LocationSettingsSnapshot
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubScroll
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LocationPickerCover(
    visible: Boolean,
    snapshot: LocationSettingsSnapshot?,
    locationService: LocationService?,
    isRadioReady: Boolean,
    onSave: suspend (LatLng) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
): Boolean {
    if (!visible) return false
    val initial = snapshot?.takeIf { it.hasLocation }?.let { LatLng(it.latitude, it.longitude) }
    LocationPickerScreen(
        initial = initial,
        locationService = locationService,
        onSave = onSave,
        onCancel = onCancel,
        isRadioReady = isRadioReady,
        modifier = modifier,
    )
    return true
}

@Composable
internal fun LocationSettingsBody(
    snap: LocationSettingsSnapshot,
    autoUpdateLocation: Boolean,
    gpsSource: GpsSource,
    deviceGps: DeviceGpsState,
    isSaving: Boolean,
    isDeviceConnected: Boolean,
    onOpenPicker: () -> Unit,
    onShareChange: (Boolean) -> Unit,
    onAutoUpdateChange: (Boolean) -> Unit,
    onGpsSourceChange: (GpsSource) -> Unit,
    onDeviceGpsToggle: (Boolean) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.preferenceHubScroll()) {
        LocationCoordinateCard(
            snap = snap,
            isSaving = isSaving,
            isDeviceConnected = isDeviceConnected,
            autoUpdateLocation = autoUpdateLocation,
            onOpenPicker = onOpenPicker,
        )
        PreferenceHubFooter(stringResource(Res.string.settings_location_footer))

        PreferenceHubSection(modifier = Modifier.padding(top = PreferenceHubSectionGap)) {
            SettingsToggleRow(
                label = stringResource(Res.string.settings_node_share_location_publicly),
                checked = snap.shareLocationPublicly,
                enabled = !isSaving,
                isRadioReady = isDeviceConnected,
                onCheckedChange = onShareChange,
                leadingIcon = AppIcons.LocationOn,
            )
            SettingsToggleRow(
                label = stringResource(Res.string.settings_location_auto_update),
                checked = autoUpdateLocation,
                enabled = !isSaving,
                isRadioReady = isDeviceConnected,
                onCheckedChange = onAutoUpdateChange,
                leadingIcon = AppIcons.MyLocation,
            )
            if (autoUpdateLocation) {
                GpsSourceRow(
                    source = gpsSource,
                    deviceHasGps = deviceGps.isSupported,
                    enabled = isDeviceConnected && !isSaving,
                    onSelect = onGpsSourceChange,
                )
            }
        }

        if (deviceGps.isSupported) {
            PreferenceHubSection(
                modifier = Modifier.padding(top = PreferenceHubSectionGap),
                title = stringResource(Res.string.settings_location_device_gps_header),
            ) {
                SettingsToggleRow(
                    label = stringResource(Res.string.settings_location_device_gps_toggle),
                    checked = deviceGps.isEnabled,
                    enabled = !isSaving,
                    isRadioReady = isDeviceConnected,
                    onCheckedChange = onDeviceGpsToggle,
                    leadingIcon = AppIcons.MyLocation,
                )
            }
            PreferenceHubFooter(stringResource(Res.string.settings_location_device_gps_footer))
        }
    }
}

@Composable
private fun LocationCoordinateCard(
    snap: LocationSettingsSnapshot,
    isSaving: Boolean,
    isDeviceConnected: Boolean,
    autoUpdateLocation: Boolean,
    onOpenPicker: () -> Unit,
) {
    PreferenceHubSection {
        Column {
            ListItem(
                leadingContent = {
                    Icon(
                        imageVector = AppIcons.Place,
                        contentDescription = null,
                    )
                },
                headlineContent = { Text(stringResource(Res.string.settings_node_set_location)) },
                supportingContent = {
                    if (snap.hasLocation) {
                        val latLabel = stringResource(Res.string.settings_location_picker_latitude)
                        val lonLabel = stringResource(Res.string.settings_location_picker_longitude)
                        Column {
                            Text("$latLabel ${formatCoord(snap.latitude)}")
                            Text("$lonLabel ${formatCoord(snap.longitude)}")
                        }
                    } else {
                        Text(
                            stringResource(Res.string.settings_node_location_not_set),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .clickable(
                        enabled = isDeviceConnected && !isSaving && !autoUpdateLocation,
                        onClick = onOpenPicker,
                    )
                    .radioConnectionHint(isDeviceConnected),
            )
        }
    }
}

@Composable
internal fun LocationDeniedAlert(visible: Boolean, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    if (!visible) return
    ConfirmAlertDialog(
        title = stringResource(Res.string.onboarding_permissions_location_alert_title),
        message = stringResource(Res.string.onboarding_permissions_location_alert_message),
        confirmLabel = stringResource(Res.string.onboarding_permissions_location_alert_open_settings),
        dismissLabel = stringResource(Res.string.localizable_common_cancel),
        onConfirm = {
            onDismiss()
            onOpenSettings()
        },
        onDismiss = onDismiss,
    )
}

/** Phone / device GPS choice; a radio with no on-board GPS shows the phone as the only source. */
@Composable
private fun GpsSourceRow(source: GpsSource, deviceHasGps: Boolean, enabled: Boolean, onSelect: (GpsSource) -> Unit) {
    if (!deviceHasGps) {
        ListItem(
            headlineContent = { Text(stringResource(Res.string.settings_location_gps_source)) },
            supportingContent = { Text(stringResource(Res.string.settings_location_gps_source_phone)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    Text(
        text = stringResource(Res.string.settings_location_gps_source),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PreferenceHubCardPadding)
            .padding(bottom = 8.dp),
    )
    val options = GpsSource.entries
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PreferenceHubCardPadding)
            .padding(bottom = PreferenceHubCardPadding),
    ) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = source == option,
                onClick = { onSelect(option) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(
                    when (option) {
                        GpsSource.PHONE -> stringResource(Res.string.settings_location_gps_source_phone)
                        GpsSource.DEVICE -> stringResource(Res.string.settings_location_gps_source_device)
                    },
                )
            }
        }
    }
}

private fun formatCoord(value: Double): String {
    val rounded = (value * COORD_SCALE).toLong() / COORD_SCALE
    return rounded.toString()
}

private const val COORD_SCALE = 100_000.0
