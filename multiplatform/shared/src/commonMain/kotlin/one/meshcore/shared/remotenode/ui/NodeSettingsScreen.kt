package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ui.TimestampFormatter
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_ok
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_reboot_confirm_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_reboot_message
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_cannot_delete
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_regions_not_empty
import one.meshcore.shared.ui.AcknowledgedAlertDialog
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubScroll
import org.jetbrains.compose.resources.stringResource

/**
 * Repeater settings screen over [NodeSettingsViewModel]: Material 3 preference-style disclosure sections
 * for device info, identity, radio, contact info, behavior, regions, security, and reboot.
 * Strings come from `remotenodes_*` resources.
 */
@Composable
fun NodeSettingsScreen(
    viewModel: NodeSettingsViewModel,
    modifier: Modifier = Modifier,
    routePathContact: ContactDto? = null,
    routePathContacts: List<ContactDto> = emptyList(),
    routePathDiscovered: List<DiscoveredNodeRecord> = emptyList(),
    userLocation: UserLocation? = null,
    locationService: LocationService? = null,
    isRadioReady: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val doneActions = nodeSettingsDoneKeyboardActions(LocalFocusManager.current)
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
            NodeDeviceInfoSection(isRadioReady = isRadioReady, state = state, viewModel = viewModel)
            NodeIdentitySection(
                isRadioReady = isRadioReady,
                state = state,
                viewModel = viewModel,
                doneActions = doneActions,
                onPickOnMap = openLocationPicker,
            )
            NodeRadioSection(
                isRadioReady = isRadioReady,
                state = state,
                viewModel = viewModel,
                doneActions = doneActions,
            )
            NodeContactInfoSection(isRadioReady = isRadioReady, state = state, viewModel = viewModel)
            NodeBehaviorSection(
                isRadioReady = isRadioReady,
                state = state,
                viewModel = viewModel,
                doneActions = doneActions,
            )
            NodeRegionsSection(isRadioReady = isRadioReady, state = state, viewModel = viewModel)
            NodeSecuritySection(
                isRadioReady = isRadioReady,
                state = state,
                viewModel = viewModel,
                doneActions = doneActions,
            )
            NodeDeviceActionsSection(isRadioReady = isRadioReady, state = state, viewModel = viewModel)
            routePathContact?.let { routeContact ->
                NodeRoutePathSection(
                    expanded = state.routePathExpanded,
                    onExpandedChange = viewModel::setRoutePathExpanded,
                    contact = routeContact,
                    contacts = routePathContacts,
                    discovered = routePathDiscovered,
                    userLocation = userLocation,
                )
            }
        }

        if (state.showRebootConfirm) {
            RemoteRebootConfirmDialog(
                isRadioReady = isRadioReady,
                title = Res.string.remotenodes_remote_nodes_settings_reboot_confirm_title,
                message = Res.string.remotenodes_remote_nodes_settings_reboot_message,
                onConfirm = viewModel::confirmReboot,
                onDismiss = { viewModel.setShowRebootConfirm(false) },
            )
        }

        state.blockedDeleteRegionName?.let { blockedName ->
            AcknowledgedAlertDialog(
                title = formatTemplate(
                    stringResource(Res.string.remotenodes_remote_nodes_settings_regions_cannot_delete),
                    listOf(blockedName),
                ),
                message = stringResource(Res.string.remotenodes_remote_nodes_settings_regions_not_empty),
                confirmLabel = stringResource(Res.string.remotenodes_remote_nodes_settings_ok),
                onDismiss = viewModel::dismissBlockedDelete,
            )
        }
    }
}

/** Leading icon on the Add Child ListItem, matching Region Management add-manually. */
internal val REGION_ADD_CHILD_ICON get() = AppIcons.Add

/** Separates the node clock's time from its date, mirroring the iOS device-time row. */
private const val DEVICE_TIME_SEPARATOR = " - "

/** The node clock in the reader's own zone; a raw UTC reading overstates or hides the node's drift. */
internal fun localDeviceTime(epochSeconds: Long, formatter: TimestampFormatter): String {
    val clock = formatter.clock(epochSeconds) ?: return formatter.unavailable
    val date = formatter.date(epochSeconds) ?: return formatter.unavailable
    return clock + DEVICE_TIME_SEPARATOR + date
}
