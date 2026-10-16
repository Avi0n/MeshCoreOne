package one.meshcore.shared.remotenode.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.battery.ui.BatteryCurveEditorContent
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_battery_curve
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_battery_curve_footer
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_ocv_load_failed
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_ocv_save_failed
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_ocv_save_no_contact
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_request_timed_out
import one.meshcore.shared.resources.settings_battery_curve_validation_not_descending
import one.meshcore.shared.resources.settings_battery_curve_validation_out_of_range
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun BatteryCurveSection(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    isSavingOcv: Boolean,
    ocvError: NodeSectionError?,
    selectedPreset: OCVPreset,
    values: List<Int>,
    isRadioReady: Boolean,
    onSelectPreset: (OCVPreset) -> Unit,
    onCommitValues: (List<Int>) -> Unit,
) {
    StatusSection(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        title = stringResource(Res.string.remotenodes_remote_nodes_status_battery_curve),
        isLoading = isSavingOcv,
        footer = stringResource(Res.string.remotenodes_remote_nodes_status_battery_curve_footer),
    ) {
        BatteryCurveEditorContent(
            availablePresets = OCVPreset.nodePresets,
            selectedPreset = selectedPreset,
            values = values,
            enabled = isRadioReady && !isSavingOcv,
            onSelectPreset = onSelectPreset,
            onCommitValues = onCommitValues,
            errorMessage = if (ocvError == null) null else ocvErrorText(ocvError),
            modifier = Modifier.radioConnectionHint(isRadioReady),
        )
    }
}

@Composable
private fun ocvErrorText(error: NodeSectionError): String {
    val message = (error as? NodeSectionError.Message)?.text
    return when {
        message == NodeStatusViewModel.OCV_LOAD_FAILED_KEY ->
            stringResource(Res.string.remotenodes_remote_nodes_status_ocv_load_failed)
        message == NodeStatusViewModel.OCV_SAVE_NO_CONTACT_KEY ->
            stringResource(Res.string.remotenodes_remote_nodes_status_ocv_save_no_contact)
        message == NodeStatusViewModel.OCV_SAVE_FAILED_KEY ->
            formattedStringResource(Res.string.remotenodes_remote_nodes_status_ocv_save_failed, "")
        message?.startsWith("out_of_range:") == true -> {
            val percent = message.removePrefix("out_of_range:").toIntOrNull() ?: 0
            formattedStringResource(
                Res.string.settings_battery_curve_validation_out_of_range,
                percent,
            )
        }
        message == "not_descending" ->
            stringResource(Res.string.settings_battery_curve_validation_not_descending)
        message != null ->
            formattedStringResource(Res.string.remotenodes_remote_nodes_status_ocv_save_failed, message)
        else -> stringResource(Res.string.remotenodes_remote_nodes_status_request_timed_out)
    }
}
