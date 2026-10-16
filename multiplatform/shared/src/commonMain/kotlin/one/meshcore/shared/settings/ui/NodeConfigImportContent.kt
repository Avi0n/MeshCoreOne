package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_config_import_import_success
import one.meshcore.shared.resources.settings_config_import_proximity_warning
import one.meshcore.shared.settings.NodeConfigService
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubFill
import one.meshcore.shared.ui.preferenceHubFormScroll
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun NodeConfigImportContent(
    state: NodeConfigImportState,
    serviceAvailable: Boolean,
    nodeConfigService: () -> NodeConfigService?,
    radioId: () -> Uuid?,
    deviceNodeName: () -> String?,
    thisDeviceLabel: String,
    scope: CoroutineScope,
    configErrorText: (Throwable) -> String?,
    unmappedConfigError: String,
    bindings: NodeConfigImportBindings,
    onSelectFile: () -> Unit,
    modifier: Modifier,
) {
    if (!serviceAvailable && !state.hasFileContent) {
        SettingsNoDeviceEmpty(modifier.preferenceHubFill())
        return
    }

    Column(modifier.preferenceHubFill()) {
        val config = state.importedConfig
        if (config == null) {
            if (state.showsFilePicker(serviceAvailable)) {
                NodeConfigImportFilePicker(
                    state = state,
                    onSelectFile = onSelectFile,
                )
            } else {
                SettingsNoDeviceEmpty(Modifier.weight(1f))
            }
        } else {
            Column(preferenceHubFormScroll()) {
                PreferenceHubSection {
                    ImportPreviewBody(
                        config = config,
                        sections = state.sections,
                        deviceDiff = state.currentDeviceDiff,
                        enabled = !state.isApplying && !state.isPreparingConfirmation,
                        onSectionsChange = { state.sections = it },
                    )
                }
                NodeConfigCaption(
                    icon = AppIcons.CellTower,
                    text = stringResource(Res.string.settings_config_import_proximity_warning),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = PreferenceHubHorizontalPadding)
                        .padding(top = PreferenceHubSectionGap),
                )
            }
            Column(Modifier.padding(horizontal = PreferenceHubHorizontalPadding)) {
                NodeConfigImportActions(
                    nodeConfigService = nodeConfigService,
                    radioId = radioId,
                    config = config,
                    sections = state.sections,
                    channelsWouldOverwrite = state.channelsWouldOverwrite,
                    deviceNodeName = deviceNodeName,
                    thisDeviceLabel = thisDeviceLabel,
                    scope = scope,
                    configErrorText = configErrorText,
                    unmappedConfigError = unmappedConfigError,
                    applyProgress = state.applyProgress,
                    applyStepDescription = state.applyStepDescription,
                    importComplete = state.importComplete,
                    onCancelImport = { state.importJob?.cancel() },
                    bindings = bindings,
                )

                if (state.importComplete && !state.isApplying) {
                    NodeConfigCaption(
                        icon = AppIcons.CheckCircle,
                        text = stringResource(Res.string.settings_config_import_import_success),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        state.errorMessage?.let { message ->
            NodeConfigErrorLabel(
                message,
                modifier = Modifier.padding(horizontal = PreferenceHubHorizontalPadding),
            )
        }
    }
}
