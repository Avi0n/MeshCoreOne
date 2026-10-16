package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_config_export_channels
import one.meshcore.shared.resources.settings_config_export_contacts
import one.meshcore.shared.resources.settings_config_export_node_identity
import one.meshcore.shared.resources.settings_config_export_other_settings
import one.meshcore.shared.resources.settings_config_export_position_settings
import one.meshcore.shared.resources.settings_config_export_radio_settings
import one.meshcore.shared.resources.settings_config_import_channel_count
import one.meshcore.shared.resources.settings_config_import_contact_count
import one.meshcore.shared.resources.settings_config_import_current
import one.meshcore.shared.resources.settings_config_import_new
import one.meshcore.shared.resources.settings_config_import_private_key_warning
import one.meshcore.shared.resources.settings_config_import_radio_warning
import one.meshcore.shared.settings.ConfigSections
import one.meshcore.shared.settings.MeshCoreNodeConfig
import one.meshcore.shared.settings.NodeConfigDeviceDiff
import one.meshcore.shared.settings.formatRadioSettingsForDisplay
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

private const val EM_DASH = "\u2014"

/** One optional block per config payload field. */
@Composable
internal fun ImportPreviewBody(
    config: MeshCoreNodeConfig,
    sections: ConfigSections,
    deviceDiff: NodeConfigDeviceDiff?,
    enabled: Boolean,
    onSectionsChange: (ConfigSections) -> Unit,
) {
    ImportPreviewIdentity(config, sections, deviceDiff, enabled, onSectionsChange)
    ImportPreviewRadio(config, sections, deviceDiff, enabled, onSectionsChange)

    config.positionSettings?.let { position ->
        SectionToggle(
            title = stringResource(Res.string.settings_config_export_position_settings),
            checked = sections.positionSettings,
            enabled = enabled,
            onCheckedChange = { onSectionsChange(sections.copy(positionSettings = it)) },
        ) {
            DiffRow(
                current = deviceDiff?.position?.let { "${it.latitude}, ${it.longitude}" } ?: EM_DASH,
                new = "${position.latitude}, ${position.longitude}",
            )
        }
    }

    if (config.otherSettings != null) {
        SectionToggle(
            title = stringResource(Res.string.settings_config_export_other_settings),
            checked = sections.otherSettings,
            enabled = enabled,
            onCheckedChange = { onSectionsChange(sections.copy(otherSettings = it)) },
        )
    }

    config.channels?.let { channels ->
        SectionToggle(
            title = stringResource(Res.string.settings_config_export_channels),
            checked = sections.channels,
            enabled = enabled,
            onCheckedChange = { onSectionsChange(sections.copy(channels = it)) },
        ) {
            Text(
                text = formattedStringResource(
                    Res.string.settings_config_import_channel_count,
                    channels.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    config.contacts?.let { contacts ->
        SectionToggle(
            title = stringResource(Res.string.settings_config_export_contacts),
            checked = sections.contacts,
            enabled = enabled,
            onCheckedChange = { onSectionsChange(sections.copy(contacts = it)) },
        ) {
            Text(
                text = formattedStringResource(
                    Res.string.settings_config_import_contact_count,
                    contacts.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImportPreviewIdentity(
    config: MeshCoreNodeConfig,
    sections: ConfigSections,
    deviceDiff: NodeConfigDeviceDiff?,
    enabled: Boolean,
    onSectionsChange: (ConfigSections) -> Unit,
) {
    if (config.name != null || config.privateKey != null || config.publicKey != null) {
        SectionToggle(
            title = stringResource(Res.string.settings_config_export_node_identity),
            checked = sections.nodeIdentity,
            enabled = enabled,
            onCheckedChange = { onSectionsChange(sections.copy(nodeIdentity = it)) },
        ) {
            if (config.name != null) {
                DiffRow(
                    current = deviceDiff?.name ?: EM_DASH,
                    new = config.name,
                )
            }
            if (config.privateKey != null) {
                NodeConfigCaption(
                    icon = AppIcons.Shield,
                    text = stringResource(Res.string.settings_config_import_private_key_warning),
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun ImportPreviewRadio(
    config: MeshCoreNodeConfig,
    sections: ConfigSections,
    deviceDiff: NodeConfigDeviceDiff?,
    enabled: Boolean,
    onSectionsChange: (ConfigSections) -> Unit,
) {
    config.radioSettings?.let { radio ->
        SectionToggle(
            title = stringResource(Res.string.settings_config_export_radio_settings),
            checked = sections.radioSettings,
            enabled = enabled,
            onCheckedChange = { onSectionsChange(sections.copy(radioSettings = it)) },
        ) {
            DiffRow(
                current = deviceDiff?.radio?.let { formatRadioSettingsForDisplay(it) } ?: EM_DASH,
                new = formatRadioSettingsForDisplay(radio),
            )
            if (deviceDiff?.radio != null && deviceDiff.radio != radio) {
                NodeConfigCaption(
                    icon = AppIcons.Warning,
                    text = stringResource(Res.string.settings_config_import_radio_warning),
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun SectionToggle(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    content: @Composable (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        SettingsToggleRow(
            label = title,
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
        if (content != null) {
            Column(
                Modifier.fillMaxWidth().padding(
                    start = PreferenceHubCardPadding,
                    end = PreferenceHubCardPadding,
                    bottom = PreferenceHubCardPadding,
                ),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun DiffRow(current: String, new: String) {
    val changed = current != new
    Text(
        text = formattedStringResource(Res.string.settings_config_import_current, current),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = formattedStringResource(Res.string.settings_config_import_new, new),
        style = MaterialTheme.typography.bodySmall,
        color = if (changed) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
