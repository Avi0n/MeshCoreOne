package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_name
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_behavior_footer
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_device_actions
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_device_info_footer
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_radio_footer
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_radio_restart_warning
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_reboot_device
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_repeater_mode
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_send_advert
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_sync_time
import one.meshcore.shared.settings.ui.SettingsToggleRow
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun NodeDeviceInfoSection(isRadioReady: Boolean, state: NodeSettingsState, viewModel: NodeSettingsViewModel) {
    RemoteDeviceInfoSection(
        onExpandedChange = viewModel::setDeviceInfoExpanded,
        isRadioReady = isRadioReady,
        fields = state,
        footer = Res.string.remotenodes_remote_nodes_settings_device_info_footer,
        onReload = viewModel::loadDeviceInfo,
    )
}

@Composable
internal fun NodeIdentitySection(
    isRadioReady: Boolean,
    state: NodeSettingsState,
    viewModel: NodeSettingsViewModel,
    doneActions: KeyboardActions,
    onPickOnMap: () -> Unit,
) {
    RemoteIdentitySection(
        onExpandedChange = viewModel::setIdentityExpanded,
        isRadioReady = isRadioReady,
        fields = state,
        doneActions = doneActions,
        nameLabel = Res.string.remotenodes_remote_nodes_name,
        onReload = viewModel::loadIdentity,
        onNameChange = viewModel::setName,
        onLatitudeChange = viewModel::setLatitudeText,
        onLongitudeChange = viewModel::setLongitudeText,
        onPickOnMap = onPickOnMap,
        onApply = viewModel::applyIdentity,
    )
}

@Composable
internal fun NodeRadioSection(
    isRadioReady: Boolean,
    state: NodeSettingsState,
    viewModel: NodeSettingsViewModel,
    doneActions: KeyboardActions,
) {
    RemoteRadioSection(
        onExpandedChange = viewModel::setRadioExpanded,
        isRadioReady = isRadioReady,
        fields = state,
        footer = Res.string.remotenodes_remote_nodes_settings_radio_footer,
        restartWarning = Res.string.remotenodes_remote_nodes_settings_radio_restart_warning,
        doneActions = doneActions,
        onReload = viewModel::loadRadio,
        onFrequencyChange = viewModel::setFrequencyText,
        onBandwidth = viewModel::setBandwidth,
        onSpreadingFactor = viewModel::setSpreadingFactor,
        onCodingRate = viewModel::setCodingRate,
        onApply = viewModel::applyRadio,
    )
}

@Composable
internal fun NodeContactInfoSection(isRadioReady: Boolean, state: NodeSettingsState, viewModel: NodeSettingsViewModel) {
    RemoteContactInfoSection(
        onExpandedChange = viewModel::setContactInfoExpanded,
        isRadioReady = isRadioReady,
        fields = state,
        onReload = viewModel::loadContactInfo,
        onOwnerInfoChange = viewModel::setOwnerInfo,
        onApply = viewModel::applyContactInfo,
    )
}

@Composable
internal fun NodeBehaviorSection(
    isRadioReady: Boolean,
    state: NodeSettingsState,
    viewModel: NodeSettingsViewModel,
    doneActions: KeyboardActions,
) {
    RemoteBehaviorSection(
        onExpandedChange = viewModel::setBehaviorExpanded,
        isRadioReady = isRadioReady,
        fields = state,
        footer = Res.string.remotenodes_remote_nodes_settings_behavior_footer,
        doneActions = doneActions,
        onReload = viewModel::loadBehavior,
        onAdvertIntervalChange = viewModel::setAdvertIntervalText,
        onFloodAdvertIntervalChange = viewModel::setFloodAdvertIntervalText,
        onFloodMaxHopsChange = viewModel::setFloodMaxHopsText,
        onApply = viewModel::applyBehavior,
        beforeFields = {
            SettingsToggleRow(
                label = stringResource(Res.string.remotenodes_remote_nodes_settings_repeater_mode),
                checked = state.repeaterEnabled,
                onCheckedChange = viewModel::setRepeaterEnabled,
            )
        },
    )
}

@Composable
internal fun NodeSecuritySection(
    isRadioReady: Boolean,
    state: NodeSettingsState,
    viewModel: NodeSettingsViewModel,
    doneActions: KeyboardActions,
) {
    RemoteSecuritySection(
        onExpandedChange = viewModel::setSecurityExpanded,
        isRadioReady = isRadioReady,
        fields = state,
        doneActions = doneActions,
        onNewPassword = viewModel::setNewPassword,
        onConfirmPassword = viewModel::setConfirmPassword,
        onChangePassword = viewModel::changePassword,
    )
}

@Composable
internal fun NodeDeviceActionsSection(
    isRadioReady: Boolean,
    state: NodeSettingsState,
    viewModel: NodeSettingsViewModel,
) {
    RemoteDeviceActionsSection(
        onExpandedChange = viewModel::setDeviceActionsExpanded,
        isRadioReady = isRadioReady,
        fields = state,
        onSendAdvert = viewModel::sendAdvert,
        onSyncTime = viewModel::syncTime,
        onReboot = { viewModel.setShowRebootConfirm(true) },
    )
}

@Composable
internal fun RemoteDeviceActionsSection(
    isRadioReady: Boolean,
    fields: RemoteDeviceActionsSectionState,
    onExpandedChange: (Boolean) -> Unit,
    onSendAdvert: () -> Unit,
    onSyncTime: () -> Unit,
    onReboot: () -> Unit,
) {
    val isSendingAdvert = fields.isSendingAdvert
    val isSyncingTime = fields.isSyncingTime
    val isRebooting = fields.isRebooting
    SettingsDisclosure(
        expanded = fields.deviceActionsExpanded,
        onExpandedChange = onExpandedChange,
        isRadioReady = isRadioReady,
        title = stringResource(Res.string.remotenodes_remote_nodes_settings_device_actions),
        isLoading = false,
    ) {
        SettingsDisclosureFields {
            ApplyButton(
                isRadioReady = isRadioReady,
                label = stringResource(Res.string.remotenodes_remote_nodes_settings_send_advert),
                enabled = !isSendingAdvert,
                isApplying = isSendingAdvert,
                onClick = onSendAdvert,
            )
            ApplyButton(
                isRadioReady = isRadioReady,
                label = stringResource(Res.string.remotenodes_remote_nodes_settings_sync_time),
                enabled = !isSyncingTime,
                isApplying = isSyncingTime,
                onClick = onSyncTime,
            )
            ApplyButton(
                isRadioReady = isRadioReady,
                label = stringResource(Res.string.remotenodes_remote_nodes_settings_reboot_device),
                enabled = !isRebooting,
                isApplying = isRebooting,
                onClick = onReboot,
            )
        }
    }
}
