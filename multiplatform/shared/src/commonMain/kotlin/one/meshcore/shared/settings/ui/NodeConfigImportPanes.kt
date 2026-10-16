package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_cancel
import one.meshcore.shared.resources.settings_config_import_apply_button
import one.meshcore.shared.resources.settings_config_import_apply_button_add
import one.meshcore.shared.resources.settings_config_import_apply_button_overwrite
import one.meshcore.shared.resources.settings_config_import_confirm_message
import one.meshcore.shared.resources.settings_config_import_confirm_message_add
import one.meshcore.shared.resources.settings_config_import_confirm_message_mixed
import one.meshcore.shared.resources.settings_config_import_confirm_message_overwrite
import one.meshcore.shared.resources.settings_config_import_confirm_title
import one.meshcore.shared.resources.settings_config_import_confirm_title_add
import one.meshcore.shared.resources.settings_config_import_confirm_title_overwrite
import one.meshcore.shared.resources.settings_config_import_select_file
import one.meshcore.shared.settings.ConfigImportConfirmKind
import one.meshcore.shared.settings.ConfigSections
import one.meshcore.shared.settings.ImportStep
import one.meshcore.shared.settings.MeshCoreNodeConfig
import one.meshcore.shared.settings.NodeConfigService
import one.meshcore.shared.settings.configImportConfirmKind
import one.meshcore.shared.ui.FilledButtonProgress
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource

private data class ConfigImportConfirmCopy(val title: String, val applyLabel: String, val message: String)

@Composable
private fun configImportConfirmCopy(kind: ConfigImportConfirmKind, deviceLabel: String): ConfigImportConfirmCopy {
    val title = when (kind) {
        ConfigImportConfirmKind.ADD -> stringResource(Res.string.settings_config_import_confirm_title_add)
        ConfigImportConfirmKind.OVERWRITE ->
            stringResource(Res.string.settings_config_import_confirm_title_overwrite)
        ConfigImportConfirmKind.MIXED, ConfigImportConfirmKind.DEFAULT ->
            stringResource(Res.string.settings_config_import_confirm_title)
    }
    val applyLabel = when (kind) {
        ConfigImportConfirmKind.ADD -> stringResource(Res.string.settings_config_import_apply_button_add)
        ConfigImportConfirmKind.OVERWRITE ->
            stringResource(Res.string.settings_config_import_apply_button_overwrite)
        ConfigImportConfirmKind.MIXED, ConfigImportConfirmKind.DEFAULT ->
            stringResource(Res.string.settings_config_import_apply_button)
    }
    val message = when (kind) {
        ConfigImportConfirmKind.ADD ->
            formattedStringResource(Res.string.settings_config_import_confirm_message_add, deviceLabel)
        ConfigImportConfirmKind.OVERWRITE ->
            formattedStringResource(Res.string.settings_config_import_confirm_message_overwrite, deviceLabel)
        ConfigImportConfirmKind.MIXED ->
            formattedStringResource(Res.string.settings_config_import_confirm_message_mixed, deviceLabel)
        ConfigImportConfirmKind.DEFAULT ->
            formattedStringResource(Res.string.settings_config_import_confirm_message, deviceLabel)
    }
    return ConfigImportConfirmCopy(title, applyLabel, message)
}

@Composable
internal fun ConfigImportConfirmDialog(
    sections: ConfigSections,
    channelsWouldOverwrite: Boolean,
    deviceNodeName: () -> String?,
    thisDeviceLabel: String,
    importedConfig: MeshCoreNodeConfig?,
    nodeConfigService: () -> NodeConfigService?,
    radioId: () -> Uuid?,
    importScope: CoroutineScope,
    describeStep: (ImportStep) -> String,
    onImportApplied: suspend () -> Unit,
    configErrorText: (Throwable) -> String?,
    unmappedConfigError: String,
    cancelledMsg: String,
    cancelledPartialMsg: String,
    failedPartialTemplate: String,
    successResetMs: Long,
    bindings: NodeConfigImportBindings,
) {
    val deviceLabel = deviceNodeName()?.takeIf { it.isNotBlank() } ?: thisDeviceLabel
    val copy = configImportConfirmCopy(
        configImportConfirmKind(sections, channelsWouldOverwrite),
        deviceLabel,
    )
    AlertDialog(
        onDismissRequest = { if (!bindings.isApplying()) bindings.setShowConfirmation(false) },
        title = { Text(copy.title) },
        text = { Text(copy.message) },
        confirmButton = {
            TextButton(
                onClick = {
                    bindings.setShowConfirmation(false)
                    if (!bindings.isConfirmationCurrent()) {
                        bindings.setErrorMessage(unmappedConfigError)
                        return@TextButton
                    }
                    val config = importedConfig ?: return@TextButton
                    val service = nodeConfigService() ?: return@TextButton
                    val id = radioId() ?: return@TextButton
                    if (bindings.isApplying()) return@TextButton
                    val selected = sections
                    launchConfigImportApply(
                        importScope,
                        ConfigImportApplyRequest(
                            importConfig = { onProgress ->
                                service.importConfig(config, selected, id, onProgress)
                            },
                            onImportApplied = {
                                if (nodeConfigService() !== service) throw CancellationException()
                                onImportApplied()
                                if (nodeConfigService() !== service) throw CancellationException()
                            },
                            describeStep = describeStep,
                            configErrorText = configErrorText,
                            unmappedConfigError = unmappedConfigError,
                            cancelledMsg = cancelledMsg,
                            cancelledPartialMsg = cancelledPartialMsg,
                            failedPartialTemplate = failedPartialTemplate,
                            successResetMs = successResetMs,
                            bindings = bindings,
                        ),
                    )
                },
            ) {
                Text(copy.applyLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = { bindings.setShowConfirmation(false) }) {
                Text(stringResource(Res.string.localizable_common_cancel))
            }
        },
    )
}

@Composable
internal fun ColumnScope.NodeConfigImportFilePicker(state: NodeConfigImportState, onSelectFile: () -> Unit) {
    Box(Modifier.weight(1f).fillMaxWidth())
    Button(
        onClick = onSelectFile,
        enabled = !state.isParsing,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PreferenceHubHorizontalPadding),
    ) {
        if (state.isParsing) FilledButtonProgress()
        Text(stringResource(Res.string.settings_config_import_select_file))
    }
}

@Composable
internal fun NodeConfigImportActions(
    nodeConfigService: () -> NodeConfigService?,
    radioId: () -> Uuid?,
    config: MeshCoreNodeConfig,
    sections: ConfigSections,
    channelsWouldOverwrite: Boolean,
    deviceNodeName: () -> String?,
    thisDeviceLabel: String,
    scope: CoroutineScope,
    configErrorText: (Throwable) -> String?,
    unmappedConfigError: String,
    applyProgress: Float,
    applyStepDescription: String,
    importComplete: Boolean,
    onCancelImport: () -> Unit,
    bindings: NodeConfigImportBindings,
) {
    val deviceLabel = deviceNodeName()?.takeIf { it.isNotBlank() } ?: thisDeviceLabel
    val applyLabel = configImportConfirmCopy(
        configImportConfirmKind(sections, channelsWouldOverwrite),
        deviceLabel,
    ).applyLabel
    if (bindings.isApplying()) {
        LinearProgressIndicator(
            progress = { applyProgress },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Text(
            text = applyStepDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onCancelImport,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.localizable_common_cancel))
        }
    } else if (!importComplete) {
        Button(
            onClick = {
                val service = nodeConfigService() ?: return@Button
                val id = radioId() ?: return@Button
                val targetMatches = { nodeConfigService() === service && radioId() == id }
                bindings.setConfirmationCurrent(targetMatches)
                val selected = sections
                launchConfigImportPreview(
                    scope,
                    ConfigImportPreviewRequest(
                        inFlight = bindings.isPreparingConfirmation() || bindings.isApplying(),
                        preview = {
                            service.previewImport(config, selected).also {
                                if (!targetMatches()) throw CancellationException()
                            }
                        },
                        configErrorText = configErrorText,
                        unmappedConfigError = unmappedConfigError,
                        bindings = bindings,
                    ),
                )
            },
            enabled = nodeConfigService() != null &&
                !bindings.isPreparingConfirmation() &&
                sections.anySectionSelected,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            if (bindings.isPreparingConfirmation()) {
                FilledButtonProgress()
            }
            Text(applyLabel)
        }
    }
}
