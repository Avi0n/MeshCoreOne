package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_advanced_radio_apply
import one.meshcore.shared.resources.settings_advanced_radio_footer
import one.meshcore.shared.resources.settings_advanced_radio_frequency
import one.meshcore.shared.resources.settings_advanced_radio_frequency_placeholder
import one.meshcore.shared.resources.settings_advanced_radio_frequency_repeat_mode_footer
import one.meshcore.shared.resources.settings_advanced_radio_header
import one.meshcore.shared.resources.settings_advanced_radio_invalid_input
import one.meshcore.shared.resources.settings_advanced_radio_tx_power
import one.meshcore.shared.resources.settings_advanced_radio_tx_power_placeholder
import one.meshcore.shared.settings.ManualRadioFormTexts
import one.meshcore.shared.settings.ManualRadioParams
import one.meshcore.shared.settings.ManualRadioParseResult
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.settings.RadioOptions
import one.meshcore.shared.settings.advancedRadioSettingsModified
import one.meshcore.shared.settings.advancedSettingsCanApply
import one.meshcore.shared.settings.parseManualRadioParams
import one.meshcore.shared.settings.toFormTexts
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.FilledButtonProgress
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.preferenceHubCardAction
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

/**
 * Manual LoRa parameter editors on Settings → Radio. Repeat Mode is the Radio page toggle;
 * frequency is disabled while Repeat Mode is on. Apply still forwards the live repeat flag so
 * firmware does not treat an omitted byte as off.
 */
@Composable
internal fun AdvancedRadioSection(
    snapshot: RadioDeviceSnapshot,
    isDeviceConnected: Boolean,
    radioWriteInFlight: Boolean,
    onRadioWriteInFlightChange: (Boolean) -> Unit,
    onApply: suspend (ManualRadioParams) -> Unit,
    onMaxRetriesExceeded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settingsErrorText = rememberSettingsErrorText()
    val scope = rememberCoroutineScope()
    val seeded = remember(snapshot) { snapshot.toFormTexts() }
    var frequencyText by remember(seeded) { mutableStateOf(seeded.frequencyMHz) }
    var bandwidthText by remember(seeded) { mutableStateOf(seeded.bandwidthKHz) }
    var sfText by remember(seeded) { mutableStateOf(seeded.spreadingFactor) }
    var crText by remember(seeded) { mutableStateOf(seeded.codingRate) }
    var txText by remember(seeded) { mutableStateOf(seeded.txPower) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var appliedOk by remember { mutableStateOf(false) }
    val invalidInputMessage = stringResource(Res.string.settings_advanced_radio_invalid_input)
    val retryAlert = rememberRetryAlertState()
    val repeatEnabled = snapshot.clientRepeat
    val frequencyEnabled = !radioWriteInFlight && !repeatEnabled

    fun applyManualParams(params: ManualRadioParams) {
        launchManualRadioApply(
            scope,
            ManualRadioApply(
                params = params,
                onApply = onApply,
                onInFlight = onRadioWriteInFlightChange,
                onError = { errorMessage = it },
                onApplied = { appliedOk = it },
                settingsErrorText = settingsErrorText,
                retryAlert = retryAlert,
                onMaxRetriesExceeded = onMaxRetriesExceeded,
            ),
        )
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PreferenceHubSection(
            title = stringResource(Res.string.settings_advanced_radio_header),
            applyHorizontalPadding = false,
        ) {
            AppOutlinedTextField(
                value = frequencyText,
                onValueChange = {
                    frequencyText = it
                    appliedOk = false
                },
                label = { Text(stringResource(Res.string.settings_advanced_radio_frequency)) },
                placeholder = { Text(stringResource(Res.string.settings_advanced_radio_frequency_placeholder)) },
                supportingText = if (repeatEnabled) {
                    {
                        Text(stringResource(Res.string.settings_advanced_radio_frequency_repeat_mode_footer))
                    }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PreferenceHubCardPadding),
                enabled = frequencyEnabled,
            )
            LoRaModulationPickers(
                copy = LoRaModulationCopy.LOCAL_RADIO,
                bandwidthSelectedLabel = RadioOptions.formatBandwidthKHz(
                    bandwidthText.toDoubleOrNull() ?: RadioOptions.bandwidthsKHz.first(),
                ),
                spreadingFactorText = sfText,
                codingRateText = crText,
                enabled = !radioWriteInFlight,
                onBandwidth = {
                    bandwidthText = it.toString()
                    appliedOk = false
                },
                onSpreadingFactor = {
                    sfText = it.toString()
                    appliedOk = false
                },
                onCodingRate = {
                    crText = it.toString()
                    appliedOk = false
                },
            )
            AppOutlinedTextField(
                value = txText,
                onValueChange = {
                    txText = it
                    appliedOk = false
                },
                label = { Text(stringResource(Res.string.settings_advanced_radio_tx_power)) },
                placeholder = { Text(stringResource(Res.string.settings_advanced_radio_tx_power_placeholder)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PreferenceHubCardPadding),
                enabled = !radioWriteInFlight,
            )

            Button(
                onClick = {
                    val parsed = parseManualRadioParams(
                        texts = ManualRadioFormTexts(
                            frequencyMHz = frequencyText,
                            bandwidthKHz = bandwidthText,
                            spreadingFactor = sfText,
                            codingRate = crText,
                            txPower = txText,
                        ),
                        maxTxPower = snapshot.maxTxPower,
                        clientRepeat = snapshot.clientRepeat.takeIf { snapshot.supportsClientRepeat },
                    )
                    when (parsed) {
                        ManualRadioParseResult.Invalid -> {
                            errorMessage = invalidInputMessage
                            appliedOk = false
                        }
                        is ManualRadioParseResult.Ok -> applyManualParams(parsed.params)
                    }
                },
                enabled = advancedSettingsCanApply(
                    isDeviceConnected = isDeviceConnected,
                    settingsModified = advancedRadioSettingsModified(
                        current = ManualRadioFormTexts(
                            frequencyMHz = frequencyText,
                            bandwidthKHz = bandwidthText,
                            spreadingFactor = sfText,
                            codingRate = crText,
                            txPower = txText,
                        ),
                        seeded = seeded,
                        clientRepeat = snapshot.clientRepeat,
                        snapshotClientRepeat = snapshot.clientRepeat,
                        supportsClientRepeat = snapshot.supportsClientRepeat,
                    ),
                    isApplying = radioWriteInFlight,
                    showSuccess = appliedOk,
                ),
                modifier = Modifier
                    .preferenceHubCardAction()
                    .radioConnectionHint(isDeviceConnected),
            ) {
                AdvancedApplyButtonLabel(
                    isApplying = radioWriteInFlight,
                    label = stringResource(Res.string.settings_advanced_radio_apply),
                )
            }
        }
        PreferenceHubFooter(
            stringResource(Res.string.settings_advanced_radio_footer),
            applyHorizontalPadding = false,
        )
    }

    SettingsWriteFeedback(retryAlert, errorMessage) { errorMessage = null }
}

@Composable
internal fun AdvancedApplyButtonLabel(isApplying: Boolean, label: String) {
    if (isApplying) {
        FilledButtonProgress()
    }
    Text(label)
}
