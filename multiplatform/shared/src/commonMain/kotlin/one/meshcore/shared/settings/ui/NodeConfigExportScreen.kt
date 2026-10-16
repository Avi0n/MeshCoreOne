package one.meshcore.shared.settings.ui

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
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_error_occurred
import one.meshcore.shared.resources.settings_config_export_channels
import one.meshcore.shared.resources.settings_config_export_channels_description
import one.meshcore.shared.resources.settings_config_export_contacts
import one.meshcore.shared.resources.settings_config_export_contacts_description
import one.meshcore.shared.resources.settings_config_export_export_full
import one.meshcore.shared.resources.settings_config_export_export_selected
import one.meshcore.shared.resources.settings_config_export_node_identity
import one.meshcore.shared.resources.settings_config_export_node_identity_description
import one.meshcore.shared.resources.settings_config_export_other_settings
import one.meshcore.shared.resources.settings_config_export_other_settings_description
import one.meshcore.shared.resources.settings_config_export_position_settings
import one.meshcore.shared.resources.settings_config_export_position_settings_description
import one.meshcore.shared.resources.settings_config_export_radio_settings
import one.meshcore.shared.resources.settings_config_export_radio_settings_description
import one.meshcore.shared.resources.settings_config_export_select_all
import one.meshcore.shared.settings.ConfigSections
import one.meshcore.shared.settings.NodeConfigJson
import one.meshcore.shared.settings.NodeConfigService
import one.meshcore.shared.settings.nodeConfigExportBasename
import one.meshcore.shared.ui.FilledButtonProgress
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.preferenceHubFill
import one.meshcore.shared.ui.preferenceHubFormScroll
import one.meshcore.shared.ui.rememberMeshBridgeErrorText
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Advanced → Export Configuration: section toggles + export to a shared JSON file.
 * Mirrors iOS `NodeConfigExportView`. Requires a live [NodeConfigService] (radio connected).
 */
@Composable
fun NodeConfigExportScreen(
    nodeConfigService: () -> NodeConfigService?,
    deviceNodeName: () -> String?,
    onShareJson: (json: String, filename: String) -> Unit,
    /** Local-time stamp `yyyy-MM-dd-HHmmss` for the export basename (platform-formatted). */
    exportTimestamp: () -> String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var sections by remember { mutableStateOf(ConfigSections()) }
    var isExporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val bridgeErrorText = rememberMeshBridgeErrorText()
    val exportFailedMessage = stringResource(Res.string.contacts_common_error_occurred)
    val service = nodeConfigService()
    if (service == null) {
        SettingsNoDeviceEmpty(modifier.preferenceHubFill())
        return
    }

    Column(modifier.preferenceHubFill()) {
        Column(preferenceHubFormScroll()) {
            PreferenceHubSection {
                SectionToggle(
                    title = stringResource(Res.string.settings_config_export_select_all),
                    description = null,
                    checked = sections.allSelected,
                    enabled = !isExporting,
                    onCheckedChange = { on ->
                        sections = if (on) sections.selectAll() else sections.deselectAll()
                    },
                )
                SectionToggle(
                    title = stringResource(Res.string.settings_config_export_node_identity),
                    description = stringResource(Res.string.settings_config_export_node_identity_description),
                    checked = sections.nodeIdentity,
                    enabled = !isExporting,
                    onCheckedChange = { sections = sections.copy(nodeIdentity = it) },
                )
                SectionToggle(
                    title = stringResource(Res.string.settings_config_export_radio_settings),
                    description = stringResource(Res.string.settings_config_export_radio_settings_description),
                    checked = sections.radioSettings,
                    enabled = !isExporting,
                    onCheckedChange = { sections = sections.copy(radioSettings = it) },
                )
                SectionToggle(
                    title = stringResource(Res.string.settings_config_export_position_settings),
                    description = stringResource(Res.string.settings_config_export_position_settings_description),
                    checked = sections.positionSettings,
                    enabled = !isExporting,
                    onCheckedChange = { sections = sections.copy(positionSettings = it) },
                )
                SectionToggle(
                    title = stringResource(Res.string.settings_config_export_other_settings),
                    description = stringResource(Res.string.settings_config_export_other_settings_description),
                    checked = sections.otherSettings,
                    enabled = !isExporting,
                    onCheckedChange = { sections = sections.copy(otherSettings = it) },
                )
                SectionToggle(
                    title = stringResource(Res.string.settings_config_export_channels),
                    description = stringResource(Res.string.settings_config_export_channels_description),
                    checked = sections.channels,
                    enabled = !isExporting,
                    onCheckedChange = { sections = sections.copy(channels = it) },
                )
                SectionToggle(
                    title = stringResource(Res.string.settings_config_export_contacts),
                    description = stringResource(Res.string.settings_config_export_contacts_description),
                    checked = sections.contacts,
                    enabled = !isExporting,
                    onCheckedChange = { sections = sections.copy(contacts = it) },
                )
            }
        }
        Button(
            onClick = {
                val selected = sections
                launchNodeConfigExport(
                    scope,
                    NodeConfigExportWrite(
                        inFlight = isExporting,
                        hasSelection = selected.anySectionSelected,
                        onExport = {
                            val config = service.exportConfig(selected)
                            val json = NodeConfigJson.encode(config)
                            val basename = nodeConfigExportBasename(
                                deviceNodeName = deviceNodeName(),
                                configName = config.name,
                                timestamp = exportTimestamp(),
                            )
                            onShareJson(json, "$basename.json")
                        },
                        onExporting = { isExporting = it },
                        onError = { errorMessage = it },
                        bridgeErrorText = bridgeErrorText,
                        exportFailedMessage = exportFailedMessage,
                    ),
                )
            },
            enabled = !isExporting && sections.anySectionSelected,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PreferenceHubHorizontalPadding)
                .padding(top = PreferenceHubSectionGap),
        ) {
            if (isExporting) {
                FilledButtonProgress()
            }
            Text(
                text = stringResource(
                    if (sections.allSelected) {
                        Res.string.settings_config_export_export_full
                    } else {
                        Res.string.settings_config_export_export_selected
                    },
                ),
            )
        }
        errorMessage?.let { message ->
            NodeConfigErrorLabel(
                message,
                modifier = Modifier.padding(horizontal = PreferenceHubHorizontalPadding),
            )
        }
    }
}

@Composable
private fun SectionToggle(
    title: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsToggleRow(
        label = title,
        supporting = description,
        checked = checked,
        enabled = enabled,
        onCheckedChange = onCheckedChange,
    )
}
