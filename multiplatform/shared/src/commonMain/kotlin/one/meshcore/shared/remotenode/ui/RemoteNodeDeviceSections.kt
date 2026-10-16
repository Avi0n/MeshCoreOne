package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import one.meshcore.shared.chat.ui.LocalTimestampFormatter
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_cancel
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_advert_interval0_hop
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_advert_interval_flood
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_advert_interval_validation
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_apply_behavior_settings
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_apply_radio_settings
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_behavior
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_device_info
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_device_time
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_firmware
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_flood_interval_validation
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_flood_max_validation
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_frequency_m_hz
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_hops
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_hrs
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_max_flood_hops
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_min
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_radio_parameters
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_reboot
import one.meshcore.shared.settings.ui.LoRaModulationCopy
import one.meshcore.shared.settings.ui.LoRaModulationPickers
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

@Composable
internal fun RemoteDeviceInfoSection(
    isRadioReady: Boolean,
    fields: RemoteDeviceInfoSectionState,
    onExpandedChange: (Boolean) -> Unit,
    footer: StringResource,
    onReload: () -> Unit,
) {
    val isLoading = fields.isLoadingDeviceInfo
    val loaded = fields.deviceInfoLoaded
    val sectionError = fields.deviceInfoSectionError
    val firmwareVersion = fields.firmwareVersion
    val deviceTimeEpochSeconds = fields.deviceTimeEpochSeconds
    val deviceTimeUtc = fields.deviceTimeUtc
    val clockDriftSeconds = fields.clockDriftSeconds
    SettingsDisclosure(
        expanded = fields.deviceInfoExpanded,
        onExpandedChange = onExpandedChange,
        isRadioReady = isRadioReady,
        title = stringResource(Res.string.remotenodes_remote_nodes_settings_device_info),
        isLoading = isLoading,
        onReload = onReload,
        footer = stringResource(footer),
    ) {
        SettingsDisclosureBody(
            isRadioReady = isRadioReady,
            isLoading = isLoading,
            loaded = loaded,
            sectionError = sectionError,
            onReload = onReload,
        ) {
            ReadOnlyRow(
                label = stringResource(Res.string.remotenodes_remote_nodes_settings_firmware),
                value = firmwareVersion.orEmpty(),
            )
            ReadOnlyRow(
                label = stringResource(Res.string.remotenodes_remote_nodes_settings_device_time),
                value =
                deviceTimeEpochSeconds?.let { localDeviceTime(it, formatter = LocalTimestampFormatter.current) }
                    ?: deviceTimeUtc.orEmpty(),
            )
            if (clockDriftSeconds != null && abs(clockDriftSeconds) >= CLOCK_DRIFT_WARNING_THRESHOLD_SECONDS) {
                ClockDriftWarning(clockDriftSeconds)
            }
        }
    }
}

@Composable
internal fun RemoteRadioSection(
    isRadioReady: Boolean,
    fields: RemoteRadioSectionState,
    onExpandedChange: (Boolean) -> Unit,
    footer: StringResource,
    restartWarning: StringResource,
    doneActions: KeyboardActions,
    onReload: () -> Unit,
    onFrequencyChange: (String) -> Unit,
    onBandwidth: (Double) -> Unit,
    onSpreadingFactor: (Int) -> Unit,
    onCodingRate: (Int) -> Unit,
    onApply: () -> Unit,
) {
    val isLoading = fields.isLoadingRadio
    val loaded = fields.radioLoaded
    val sectionError = fields.radioSectionError
    val radioModified = fields.radioModified
    val frequencyText = fields.frequencyText
    val bandwidthText = fields.bandwidthText
    val spreadingFactorText = fields.spreadingFactorText
    val codingRateText = fields.codingRateText
    val isApplying = fields.isApplyingRadio
    SettingsDisclosure(
        expanded = fields.radioExpanded,
        onExpandedChange = onExpandedChange,
        isRadioReady = isRadioReady,
        title = stringResource(Res.string.remotenodes_remote_nodes_settings_radio_parameters),
        isLoading = isLoading,
        onReload = onReload,
        footer = stringResource(footer),
    ) {
        SettingsDisclosureBody(
            isRadioReady = isRadioReady,
            isLoading = isLoading,
            loaded = loaded,
            sectionError = sectionError,
            onReload = onReload,
        ) {
            SettingsDisclosureFields {
                if (radioModified) {
                    RadioRestartWarning(stringResource(restartWarning))
                }
                AppOutlinedTextField(
                    value = frequencyText,
                    onValueChange = onFrequencyChange,
                    label = { Text(stringResource(Res.string.remotenodes_remote_nodes_settings_frequency_m_hz)) },
                    singleLine = true,
                    keyboardOptions = NODE_SETTINGS_DECIMAL_KEYBOARD,
                    keyboardActions = doneActions,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LoRaModulationPickers(
                copy = LoRaModulationCopy.REMOTE_NODE,
                bandwidthSelectedLabel = bandwidthText,
                spreadingFactorText = spreadingFactorText,
                codingRateText = codingRateText,
                enabled = !isApplying,
                onBandwidth = onBandwidth,
                onSpreadingFactor = onSpreadingFactor,
                onCodingRate = onCodingRate,
            )
            SettingsDisclosureFields {
                ApplyButton(
                    isRadioReady = isRadioReady,
                    label = stringResource(Res.string.remotenodes_remote_nodes_settings_apply_radio_settings),
                    enabled = radioModified && !isApplying,
                    isApplying = isApplying,
                    onClick = onApply,
                )
            }
        }
    }
}

@Composable
internal fun RemoteBehaviorSection(
    isRadioReady: Boolean,
    fields: RemoteBehaviorSectionState,
    onExpandedChange: (Boolean) -> Unit,
    footer: StringResource,
    doneActions: KeyboardActions,
    onReload: () -> Unit,
    onAdvertIntervalChange: (String) -> Unit,
    onFloodAdvertIntervalChange: (String) -> Unit,
    onFloodMaxHopsChange: (String) -> Unit,
    onApply: () -> Unit,
    beforeFields: @Composable () -> Unit = {},
) {
    val isLoading = fields.isLoadingBehavior
    val loaded = fields.behaviorLoaded
    val sectionError = fields.behaviorSectionError
    val advertIntervalText = fields.advertIntervalText
    val floodAdvertIntervalText = fields.floodAdvertIntervalText
    val floodMaxHopsText = fields.floodMaxHopsText
    val advertIntervalInvalid = fields.behaviorValidation?.advertIntervalInvalid == true
    val floodIntervalInvalid = fields.behaviorValidation?.floodIntervalInvalid == true
    val floodMaxHopsInvalid = fields.behaviorValidation?.floodMaxHopsInvalid == true
    val modified = fields.behaviorModified
    val isApplying = fields.isApplyingBehavior
    val applySuccess = fields.behaviorApplySuccess
    SettingsDisclosure(
        expanded = fields.behaviorExpanded,
        onExpandedChange = onExpandedChange,
        isRadioReady = isRadioReady,
        title = stringResource(Res.string.remotenodes_remote_nodes_settings_behavior),
        isLoading = isLoading,
        onReload = onReload,
        footer = stringResource(footer),
    ) {
        SettingsDisclosureBody(
            isRadioReady = isRadioReady,
            isLoading = isLoading,
            loaded = loaded,
            sectionError = sectionError,
            onReload = onReload,
        ) {
            beforeFields()
            SettingsDisclosureFields {
                AppOutlinedTextField(
                    value = advertIntervalText,
                    onValueChange = onAdvertIntervalChange,
                    label = {
                        Text(stringResource(Res.string.remotenodes_remote_nodes_settings_advert_interval0_hop))
                    },
                    suffix = { Text(stringResource(Res.string.remotenodes_remote_nodes_settings_min)) },
                    singleLine = true,
                    isError = advertIntervalInvalid,
                    supportingText = settingsFieldValidationText(
                        advertIntervalInvalid,
                        Res.string.remotenodes_remote_nodes_settings_advert_interval_validation,
                    ),
                    keyboardOptions = NODE_SETTINGS_NUMBER_KEYBOARD,
                    keyboardActions = doneActions,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppOutlinedTextField(
                    value = floodAdvertIntervalText,
                    onValueChange = onFloodAdvertIntervalChange,
                    label = {
                        Text(stringResource(Res.string.remotenodes_remote_nodes_settings_advert_interval_flood))
                    },
                    suffix = { Text(stringResource(Res.string.remotenodes_remote_nodes_settings_hrs)) },
                    singleLine = true,
                    isError = floodIntervalInvalid,
                    supportingText = settingsFieldValidationText(
                        floodIntervalInvalid,
                        Res.string.remotenodes_remote_nodes_settings_flood_interval_validation,
                    ),
                    keyboardOptions = NODE_SETTINGS_NUMBER_KEYBOARD,
                    keyboardActions = doneActions,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppOutlinedTextField(
                    value = floodMaxHopsText,
                    onValueChange = onFloodMaxHopsChange,
                    label = { Text(stringResource(Res.string.remotenodes_remote_nodes_settings_max_flood_hops)) },
                    suffix = { Text(stringResource(Res.string.remotenodes_remote_nodes_settings_hops)) },
                    singleLine = true,
                    isError = floodMaxHopsInvalid,
                    supportingText = settingsFieldValidationText(
                        floodMaxHopsInvalid,
                        Res.string.remotenodes_remote_nodes_settings_flood_max_validation,
                    ),
                    keyboardOptions = NODE_SETTINGS_NUMBER_KEYBOARD,
                    keyboardActions = doneActions,
                    modifier = Modifier.fillMaxWidth(),
                )
                ApplyButton(
                    isRadioReady = isRadioReady,
                    label = stringResource(Res.string.remotenodes_remote_nodes_settings_apply_behavior_settings),
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
internal fun RemoteRebootConfirmDialog(
    isRadioReady: Boolean,
    title: StringResource,
    message: StringResource,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmAlertDialog(
        title = stringResource(title),
        message = stringResource(message),
        confirmLabel = stringResource(Res.string.remotenodes_remote_nodes_settings_reboot),
        dismissLabel = stringResource(Res.string.remotenodes_remote_nodes_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmColor = if (isRadioReady) MaterialTheme.colorScheme.error else Color.Unspecified,
        confirmEnabled = isRadioReady,
        confirmModifier = Modifier.radioConnectionHint(isRadioReady),
    )
}
