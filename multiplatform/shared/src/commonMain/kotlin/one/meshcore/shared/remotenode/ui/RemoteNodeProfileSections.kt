package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import one.meshcore.shared.contact.MAX_USABLE_NAME_BYTES
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_apply_contact_info
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_apply_identity_settings
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_change_password
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_confirm_password
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_contact_info
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_contact_info_footer
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_contact_info_placeholder
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_identity_footer
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_identity_location
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_latitude
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_latitude_validation
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_longitude
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_longitude_validation
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_name_validation
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_new_password
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_security
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_security_footer
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The per-field range hint shown under a rejected settings field, or nothing while the field is valid.
 * Without it a rejected value only produces a generic apply failure with no range to correct it against.
 */
@Composable
internal fun settingsFieldValidationText(
    show: Boolean,
    resource: StringResource,
    vararg args: Any,
): (@Composable () -> Unit)? {
    if (!show) return null
    val message = formattedStringResource(resource, *args)
    return { Text(message, color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun RemoteIdentitySection(
    isRadioReady: Boolean,
    fields: RemoteIdentitySectionState,
    onExpandedChange: (Boolean) -> Unit,
    doneActions: KeyboardActions,
    nameLabel: StringResource,
    onReload: () -> Unit,
    onNameChange: (String) -> Unit,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    onPickOnMap: () -> Unit,
    onApply: () -> Unit,
) {
    val isLoading = fields.isLoadingIdentity
    val loaded = fields.identityLoaded
    val sectionError = fields.identitySectionError
    val name = fields.name
    val nameTooLong = fields.identityValidation?.nameTooLong == true
    val latitudeText = fields.latitudeText
    val latitudeOutOfRange = fields.identityValidation?.latitudeOutOfRange == true
    val longitudeText = fields.longitudeText
    val longitudeOutOfRange = fields.identityValidation?.longitudeOutOfRange == true
    val modified = fields.identityModified
    val isApplying = fields.isApplyingIdentity
    val applySuccess = fields.identityApplySuccess
    SettingsDisclosure(
        expanded = fields.identityExpanded,
        onExpandedChange = onExpandedChange,
        isRadioReady = isRadioReady,
        title = stringResource(Res.string.remotenodes_remote_nodes_settings_identity_location),
        isLoading = isLoading,
        onReload = onReload,
        footer = stringResource(Res.string.remotenodes_remote_nodes_settings_identity_footer),
    ) {
        SettingsDisclosureBody(
            isRadioReady = isRadioReady,
            isLoading = isLoading,
            loaded = loaded,
            sectionError = sectionError,
            onReload = onReload,
        ) {
            SettingsDisclosureFields {
                AppOutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(nameLabel)) },
                    singleLine = true,
                    isError = nameTooLong,
                    supportingText = settingsFieldValidationText(
                        nameTooLong,
                        Res.string.remotenodes_remote_nodes_settings_name_validation,
                        MAX_USABLE_NAME_BYTES,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                AppOutlinedTextField(
                    value = latitudeText,
                    onValueChange = onLatitudeChange,
                    label = { Text(stringResource(Res.string.remotenodes_remote_nodes_settings_latitude)) },
                    singleLine = true,
                    isError = latitudeOutOfRange,
                    supportingText = settingsFieldValidationText(
                        latitudeOutOfRange,
                        Res.string.remotenodes_remote_nodes_settings_latitude_validation,
                    ),
                    keyboardOptions = NODE_SETTINGS_DECIMAL_KEYBOARD,
                    keyboardActions = doneActions,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppOutlinedTextField(
                    value = longitudeText,
                    onValueChange = onLongitudeChange,
                    label = { Text(stringResource(Res.string.remotenodes_remote_nodes_settings_longitude)) },
                    singleLine = true,
                    isError = longitudeOutOfRange,
                    supportingText = settingsFieldValidationText(
                        longitudeOutOfRange,
                        Res.string.remotenodes_remote_nodes_settings_longitude_validation,
                    ),
                    keyboardOptions = NODE_SETTINGS_DECIMAL_KEYBOARD,
                    keyboardActions = doneActions,
                    modifier = Modifier.fillMaxWidth(),
                )
                PickOnMapButton(onClick = onPickOnMap)
                ApplyButton(
                    isRadioReady = isRadioReady,
                    label = stringResource(Res.string.remotenodes_remote_nodes_settings_apply_identity_settings),
                    enabled = modified && !isApplying,
                    isApplying = isApplying,
                    showSuccess = applySuccess,
                    onClick = onApply,
                )
            }
        }
    }
}

@Composable
internal fun RemoteContactInfoSection(
    isRadioReady: Boolean,
    fields: RemoteContactInfoSectionState,
    onExpandedChange: (Boolean) -> Unit,
    onReload: () -> Unit,
    onOwnerInfoChange: (String) -> Unit,
    onApply: () -> Unit,
) {
    val isLoading = fields.isLoadingContactInfo
    val loaded = fields.contactInfoLoaded
    val sectionError = fields.contactInfoSectionError
    val ownerInfo = fields.ownerInfo
    val ownerInfoTooLong = fields.ownerInfoTooLong
    val applyBanner = fields.applyBanner
    val modified = fields.contactInfoModified
    val isApplying = fields.isApplyingContactInfo
    val applySuccess = fields.contactInfoApplySuccess
    SettingsDisclosure(
        expanded = fields.contactInfoExpanded,
        onExpandedChange = onExpandedChange,
        isRadioReady = isRadioReady,
        title = stringResource(Res.string.remotenodes_remote_nodes_settings_contact_info),
        isLoading = isLoading,
        onReload = onReload,
        footer = stringResource(Res.string.remotenodes_remote_nodes_settings_contact_info_footer),
    ) {
        SettingsDisclosureBody(
            isRadioReady = isRadioReady,
            isLoading = isLoading,
            loaded = loaded,
            sectionError = sectionError,
            onReload = onReload,
        ) {
            SettingsDisclosureFields {
                AppOutlinedTextField(
                    value = ownerInfo,
                    onValueChange = onOwnerInfoChange,
                    label = {
                        Text(stringResource(Res.string.remotenodes_remote_nodes_settings_contact_info))
                    },
                    supportingText = {
                        Text(stringResource(Res.string.remotenodes_remote_nodes_settings_contact_info_placeholder))
                    },
                    minLines = 3,
                    // The firmware truncates past its owner-info cap, so an over-long value would be
                    // silently cut on the node rather than rejected here.
                    isError = ownerInfoTooLong,
                    modifier = Modifier.fillMaxWidth(),
                )
                OwnerInfoCharCount(length = ownerInfo.length, tooLong = ownerInfoTooLong)
                ContactInfoApplyError(applyBanner)
                ApplyButton(
                    isRadioReady = isRadioReady,
                    label = stringResource(Res.string.remotenodes_remote_nodes_settings_apply_contact_info),
                    enabled = modified && !isApplying && !ownerInfoTooLong,
                    isApplying = isApplying,
                    showSuccess = applySuccess,
                    onClick = onApply,
                )
            }
        }
    }
}

@Composable
internal fun RemoteSecuritySection(
    isRadioReady: Boolean,
    fields: RemoteSecuritySectionState,
    onExpandedChange: (Boolean) -> Unit,
    doneActions: KeyboardActions,
    onNewPassword: (String) -> Unit,
    onConfirmPassword: (String) -> Unit,
    onChangePassword: () -> Unit,
) {
    val newPassword = fields.newPassword
    val confirmPassword = fields.confirmPassword
    val isChangingPassword = fields.isChangingPassword
    val changePasswordSuccess = fields.changePasswordSuccess
    SettingsDisclosure(
        expanded = fields.securityExpanded,
        onExpandedChange = onExpandedChange,
        isRadioReady = isRadioReady,
        title = stringResource(Res.string.remotenodes_remote_nodes_settings_security),
        isLoading = false,
        footer = stringResource(Res.string.remotenodes_remote_nodes_settings_security_footer),
    ) {
        SettingsDisclosureFields {
            AppOutlinedTextField(
                value = newPassword,
                onValueChange = onNewPassword,
                label = { Text(stringResource(Res.string.remotenodes_remote_nodes_settings_new_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = NODE_SETTINGS_PASSWORD_KEYBOARD,
                keyboardActions = doneActions,
                modifier = Modifier.fillMaxWidth(),
            )
            AppOutlinedTextField(
                value = confirmPassword,
                onValueChange = onConfirmPassword,
                label = { Text(stringResource(Res.string.remotenodes_remote_nodes_settings_confirm_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = NODE_SETTINGS_PASSWORD_KEYBOARD,
                keyboardActions = doneActions,
                modifier = Modifier.fillMaxWidth(),
            )
            ApplyButton(
                isRadioReady = isRadioReady,
                label = stringResource(Res.string.remotenodes_remote_nodes_settings_change_password),
                enabled = passwordChangeApplyEnabled(
                    newPassword = newPassword,
                    confirmPassword = confirmPassword,
                    isChanging = isChangingPassword,
                ),
                isApplying = isChangingPassword,
                showSuccess = changePasswordSuccess,
                onClick = onChangePassword,
            )
        }
    }
}
