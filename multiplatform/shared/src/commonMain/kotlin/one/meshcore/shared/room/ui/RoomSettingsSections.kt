package one.meshcore.shared.room.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import one.meshcore.shared.remotenode.ui.ApplyButton
import one.meshcore.shared.remotenode.ui.NODE_SETTINGS_PASSWORD_KEYBOARD
import one.meshcore.shared.remotenode.ui.RemoteBehaviorSection
import one.meshcore.shared.remotenode.ui.RemoteContactInfoSection
import one.meshcore.shared.remotenode.ui.RemoteDeviceActionsSection
import one.meshcore.shared.remotenode.ui.RemoteDeviceInfoSection
import one.meshcore.shared.remotenode.ui.RemoteIdentitySection
import one.meshcore.shared.remotenode.ui.RemoteRadioSection
import one.meshcore.shared.remotenode.ui.RemoteSecuritySection
import one.meshcore.shared.remotenode.ui.SettingsDisclosure
import one.meshcore.shared.remotenode.ui.SettingsDisclosureBody
import one.meshcore.shared.remotenode.ui.SettingsDisclosureFields
import one.meshcore.shared.remotenode.ui.nodeSettingsDoneKeyboardActions
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_settings_allow_read_only
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_settings_allow_read_only_footer
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_settings_apply_room_settings
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_settings_behavior_footer
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_settings_guest_password
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_settings_radio_restart_warning
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_settings_room_settings_footer
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_settings_room_settings_section
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_failed_to_load
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_loading
import one.meshcore.shared.settings.ui.SettingsToggleRow
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.PreferenceHubStatusRow
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RoomAccessSection(isRadioReady: Boolean, state: RoomSettingsUiState, viewModel: RoomSettingsViewModel) {
    SettingsDisclosure(
        expanded = state.roomAccessExpanded,
        onExpandedChange = viewModel::setRoomAccessExpanded,
        isRadioReady = isRadioReady,
        title = stringResource(Res.string.remotenodes_remote_nodes_room_settings_room_settings_section),
        isLoading = state.isLoadingRoomAccess,
        onReload = viewModel::loadRoomAccess,
        footer = stringResource(Res.string.remotenodes_remote_nodes_room_settings_room_settings_footer),
    ) {
        SettingsDisclosureBody(
            isRadioReady = isRadioReady,
            isLoading = state.isLoadingRoomAccess,
            loaded = state.roomAccessLoaded,
            sectionError = state.roomAccessSectionError,
            onReload = viewModel::loadRoomAccess,
        ) {
            val loadCopy = RoomAccessLoadCopy(
                loading = stringResource(Res.string.remotenodes_remote_nodes_settings_loading),
                failed = stringResource(Res.string.remotenodes_remote_nodes_settings_failed_to_load),
            )
            val passwordCaption = roomAccessFieldLoadCaption(
                valueLoaded = state.guestPassword != null,
                isLoading = state.isLoadingRoomAccess,
                hasError = state.roomAccessSectionError,
                copy = loadCopy,
            )
            val readOnlyCaption = roomAccessFieldLoadCaption(
                valueLoaded = state.allowReadOnly != null,
                isLoading = state.isLoadingRoomAccess,
                hasError = state.roomAccessSectionError,
                copy = loadCopy,
            )
            SettingsDisclosureFields {
                AppOutlinedTextField(
                    value = state.guestPassword.orEmpty(),
                    onValueChange = viewModel::setGuestPassword,
                    enabled = roomAccessFieldEnabled(
                        valueLoaded = state.guestPassword != null,
                        isApplying = state.isApplyingRoomAccess,
                    ),
                    label = {
                        Text(stringResource(Res.string.remotenodes_remote_nodes_room_settings_guest_password))
                    },
                    trailingIcon = passwordCaption?.let { caption ->
                        { Text(caption, style = MaterialTheme.typography.labelSmall) }
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = NODE_SETTINGS_PASSWORD_KEYBOARD,
                    keyboardActions = nodeSettingsDoneKeyboardActions(LocalFocusManager.current),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SettingsToggleRow(
                label = stringResource(Res.string.remotenodes_remote_nodes_room_settings_allow_read_only),
                supporting = stringResource(
                    Res.string.remotenodes_remote_nodes_room_settings_allow_read_only_footer,
                ),
                checked = state.allowReadOnly == true,
                onCheckedChange = viewModel::setAllowReadOnly,
                enabled = roomAccessFieldEnabled(
                    valueLoaded = state.allowReadOnly != null,
                    isApplying = state.isApplyingRoomAccess,
                ),
                valueDescription = readOnlyCaption,
            )
            if (readOnlyCaption != null) {
                PreferenceHubStatusRow(readOnlyCaption)
            }
            SettingsDisclosureFields {
                ApplyButton(
                    isRadioReady = isRadioReady,
                    label = stringResource(Res.string.remotenodes_remote_nodes_room_settings_apply_room_settings),
                    enabled = state.roomAccessModified && !state.isApplyingRoomAccess,
                    isApplying = state.isApplyingRoomAccess,
                    showSuccess = state.roomAccessApplySuccess,
                    onClick = viewModel::applyRoomAccess,
                )
            }
        }
    }
}

@Composable
internal fun RoomRadioSection(isRadioReady: Boolean, state: RoomSettingsUiState, viewModel: RoomSettingsViewModel) {
    RemoteRadioSection(
        onExpandedChange = viewModel::setRadioExpanded,
        isRadioReady = isRadioReady,
        fields = state,
        footer = roomRadioFooter(),
        restartWarning = Res.string.remotenodes_remote_nodes_room_settings_radio_restart_warning,
        doneActions = nodeSettingsDoneKeyboardActions(LocalFocusManager.current),
        onReload = viewModel::loadRadio,
        onFrequencyChange = viewModel::setFrequencyText,
        onBandwidth = viewModel::setBandwidth,
        onSpreadingFactor = viewModel::setSpreadingFactor,
        onCodingRate = viewModel::setCodingRate,
        onApply = viewModel::applyRadio,
    )
}

@Composable
internal fun RoomBehaviorSection(isRadioReady: Boolean, state: RoomSettingsUiState, viewModel: RoomSettingsViewModel) {
    RemoteBehaviorSection(
        onExpandedChange = viewModel::setBehaviorExpanded,
        isRadioReady = isRadioReady,
        fields = state,
        footer = Res.string.remotenodes_remote_nodes_room_settings_behavior_footer,
        doneActions = nodeSettingsDoneKeyboardActions(LocalFocusManager.current),
        onReload = viewModel::loadBehavior,
        onAdvertIntervalChange = viewModel::setAdvertIntervalText,
        onFloodAdvertIntervalChange = viewModel::setFloodAdvertIntervalText,
        onFloodMaxHopsChange = viewModel::setFloodMaxHopsText,
        onApply = viewModel::applyBehavior,
    )
}

@Composable
internal fun RoomIdentitySection(
    isRadioReady: Boolean,
    state: RoomSettingsUiState,
    viewModel: RoomSettingsViewModel,
    onPickLocation: () -> Unit,
) {
    RemoteIdentitySection(
        onExpandedChange = viewModel::setIdentityExpanded,
        isRadioReady = isRadioReady,
        fields = state,
        doneActions = nodeSettingsDoneKeyboardActions(LocalFocusManager.current),
        nameLabel = roomIdentityNameLabel(),
        onReload = viewModel::loadIdentity,
        onNameChange = viewModel::setName,
        onLatitudeChange = viewModel::setLatitudeText,
        onLongitudeChange = viewModel::setLongitudeText,
        onPickOnMap = onPickLocation,
        onApply = viewModel::applyIdentity,
    )
}

@Composable
internal fun RoomContactInfoSection(
    isRadioReady: Boolean,
    state: RoomSettingsUiState,
    viewModel: RoomSettingsViewModel,
) {
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
internal fun RoomSecuritySection(isRadioReady: Boolean, state: RoomSettingsUiState, viewModel: RoomSettingsViewModel) {
    RemoteSecuritySection(
        onExpandedChange = viewModel::setSecurityExpanded,
        isRadioReady = isRadioReady,
        fields = state,
        doneActions = nodeSettingsDoneKeyboardActions(LocalFocusManager.current),
        onNewPassword = viewModel::setNewPassword,
        onConfirmPassword = viewModel::setConfirmPassword,
        onChangePassword = viewModel::changePassword,
    )
}

@Composable
internal fun RoomDeviceInfoSection(
    isRadioReady: Boolean,
    state: RoomSettingsUiState,
    viewModel: RoomSettingsViewModel,
) {
    RemoteDeviceInfoSection(
        onExpandedChange = viewModel::setDeviceInfoExpanded,
        isRadioReady = isRadioReady,
        fields = state,
        footer = roomDeviceInfoFooter(),
        onReload = viewModel::loadDeviceInfo,
    )
}

@Composable
internal fun RoomDeviceActionsSection(
    isRadioReady: Boolean,
    state: RoomSettingsUiState,
    viewModel: RoomSettingsViewModel,
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
