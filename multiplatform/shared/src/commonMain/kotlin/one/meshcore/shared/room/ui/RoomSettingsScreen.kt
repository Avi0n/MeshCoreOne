package one.meshcore.shared.room.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.remotenode.ui.DismissibleApplyBanner
import one.meshcore.shared.remotenode.ui.RemoteRebootConfirmDialog
import one.meshcore.shared.remotenode.ui.RemoteSettingsLocationHost
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_name
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_settings_reboot_confirm_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_settings_reboot_message
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_device_info_footer
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_radio_footer
import one.meshcore.shared.ui.preferenceHubScroll
import org.jetbrains.compose.resources.StringResource

/**
 * Room management surface over [RoomSettingsViewModel]: the room-only Room Access and Behavior sections
 * plus the shared radio, identity (with map picker), contact-info, security, device-info, and
 * device-action sections, in the iOS `RoomSettingsView` order.
 */
@Composable
fun RoomSettingsScreen(
    viewModel: RoomSettingsViewModel,
    modifier: Modifier = Modifier,
    locationService: LocationService? = null,
    isRadioReady: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RemoteSettingsLocationHost(
        loadKey = viewModel,
        onLoad = viewModel::loadOpenSections,
        latitudeText = state.latitudeText,
        longitudeText = state.longitudeText,
        locationService = locationService,
        isRadioReady = isRadioReady,
        onSave = viewModel::setLocationFromPicker,
        modifier = modifier,
    ) { openLocationPicker ->
        Column(modifier = modifier.preferenceHubScroll()) {
            DismissibleApplyBanner(state.applyBanner, viewModel::clearApplyBanner)

            RoomAccessSection(isRadioReady = isRadioReady, state = state, viewModel = viewModel)
            RoomRadioSection(isRadioReady = isRadioReady, state = state, viewModel = viewModel)
            RoomBehaviorSection(isRadioReady = isRadioReady, state = state, viewModel = viewModel)
            RoomIdentitySection(
                isRadioReady = isRadioReady,
                state = state,
                viewModel = viewModel,
                onPickLocation = openLocationPicker,
            )
            RoomContactInfoSection(isRadioReady = isRadioReady, state = state, viewModel = viewModel)
            RoomSecuritySection(isRadioReady = isRadioReady, state = state, viewModel = viewModel)
            RoomDeviceInfoSection(isRadioReady = isRadioReady, state = state, viewModel = viewModel)
            RoomDeviceActionsSection(isRadioReady = isRadioReady, state = state, viewModel = viewModel)
        }

        if (state.showRebootConfirm) {
            RemoteRebootConfirmDialog(
                isRadioReady = isRadioReady,
                title = Res.string.remotenodes_remote_nodes_room_settings_reboot_confirm_title,
                message = Res.string.remotenodes_remote_nodes_room_settings_reboot_message,
                onConfirm = viewModel::confirmReboot,
                onDismiss = { viewModel.setShowRebootConfirm(false) },
            )
        }
    }
}

/** iOS `RemoteNodeIdentitySection` uses `RemoteNodes.name`, not Settings' node-name row. */
internal fun roomIdentityNameLabel(): StringResource = Res.string.remotenodes_remote_nodes_name

/** Footer under Apply, matching `NodeRadioSettingsSection` as used by `RoomSettingsView`. */
internal fun roomRadioFooter(): StringResource = Res.string.remotenodes_remote_nodes_settings_radio_footer

/** Footer under firmware and clock, matching `NodeDeviceInfoSection` as used by `RoomSettingsView`. */
internal fun roomDeviceInfoFooter(): StringResource = Res.string.remotenodes_remote_nodes_settings_device_info_footer

/** iOS `RoomAccessSection` disables a field until that CLI fetch stores a value. */
internal fun roomAccessFieldEnabled(valueLoaded: Boolean, isApplying: Boolean): Boolean = valueLoaded && !isApplying

/**
 * iOS `SettingsLoadPlaceholder` on a still-nil guest password or allow-read-only field.
 */
internal fun roomAccessFieldLoadCaption(
    valueLoaded: Boolean,
    isLoading: Boolean,
    hasError: Boolean,
    copy: RoomAccessLoadCopy,
): String? = when {
    valueLoaded -> null
    isLoading -> copy.loading
    hasError -> copy.failed
    else -> copy.dash
}
