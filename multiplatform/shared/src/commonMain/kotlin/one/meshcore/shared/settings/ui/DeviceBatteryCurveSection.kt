package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import one.meshcore.shared.battery.BatteryOcv
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.battery.OcvEditorState
import one.meshcore.shared.battery.ui.BatteryCurveEditorContent
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_alert_retry_unable_to_save
import one.meshcore.shared.resources.settings_battery_curve_footer
import one.meshcore.shared.resources.settings_battery_curve_header
import one.meshcore.shared.resources.settings_battery_curve_validation_not_descending
import one.meshcore.shared.resources.settings_battery_curve_validation_out_of_range
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DeviceBatteryCurveSection(
    deviceOcvSnapshot: () -> OcvEditorState?,
    isDeviceConnected: Boolean,
    onSave: suspend (OCVPreset, List<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val seeded = deviceOcvSnapshot()
    var selectedPreset by remember(seeded) {
        mutableStateOf(seeded?.preset ?: OCVPreset.LI_ION)
    }
    var ocvValues by remember(seeded) {
        mutableStateOf(seeded?.values ?: OCVPreset.LI_ION.ocvArray)
    }
    var isSaving by remember { mutableStateOf(false) }
    var errorKey by remember { mutableStateOf<String?>(null) }
    val enabled = isDeviceConnected && !isSaving

    fun persist(preset: OCVPreset, values: List<Int>) {
        launchBatteryCurveSave(
            scope,
            BatteryCurveSave(
                preset = preset,
                values = values,
                onSave = onSave,
                onSaving = { isSaving = it },
                onErrorKey = { errorKey = it },
            ),
        )
    }

    Column(modifier.fillMaxWidth()) {
        PreferenceHubSection(
            title = stringResource(Res.string.settings_battery_curve_header),
            applyHorizontalPadding = false,
        ) {
            BatteryCurveEditorContent(
                availablePresets = OCVPreset.selectablePresets,
                selectedPreset = selectedPreset,
                values = ocvValues,
                enabled = enabled,
                onSelectPreset = { preset ->
                    if (preset == OCVPreset.CUSTOM) return@BatteryCurveEditorContent
                    selectedPreset = preset
                    ocvValues = preset.ocvArray
                    errorKey = null
                    persist(preset, preset.ocvArray)
                },
                onCommitValues = { values ->
                    val matchesNamedPreset =
                        selectedPreset != OCVPreset.CUSTOM && values == selectedPreset.ocvArray
                    if (matchesNamedPreset) return@BatteryCurveEditorContent
                    val validation = BatteryOcv.validationErrorKey(values)
                    if (validation != null) {
                        errorKey = validation
                        return@BatteryCurveEditorContent
                    }
                    selectedPreset = OCVPreset.CUSTOM
                    ocvValues = values
                    errorKey = null
                    persist(OCVPreset.CUSTOM, values)
                },
                errorMessage = advancedOcvErrorText(errorKey),
            )
        }
        PreferenceHubFooter(
            stringResource(Res.string.settings_battery_curve_footer),
            applyHorizontalPadding = false,
        )
    }
}

@Composable
private fun advancedOcvErrorText(errorKey: String?): String? {
    if (errorKey == null) return null
    return when {
        errorKey.startsWith("out_of_range:") -> {
            val percent = errorKey.removePrefix("out_of_range:").toIntOrNull() ?: 0
            formattedStringResource(Res.string.settings_battery_curve_validation_out_of_range, percent)
        }
        errorKey == "not_descending" ->
            stringResource(Res.string.settings_battery_curve_validation_not_descending)
        errorKey == BATTERY_CURVE_SAVE_FAILED_KEY ->
            stringResource(Res.string.settings_alert_retry_unable_to_save)
        else -> errorKey
    }
}
