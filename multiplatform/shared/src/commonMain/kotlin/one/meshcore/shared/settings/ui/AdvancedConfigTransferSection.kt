package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_config_export_export
import one.meshcore.shared.resources.settings_config_export_section_footer
import one.meshcore.shared.resources.settings_config_export_section_title
import one.meshcore.shared.resources.settings_config_import_import_config
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AdvancedConfigTransferSection(
    isDeviceConnected: Boolean,
    onOpenConfigExport: (() -> Unit)?,
    onOpenConfigImport: (() -> Unit)?,
) {
    if (onOpenConfigExport == null && onOpenConfigImport == null) return
    PreferenceHubSection(
        modifier = Modifier.padding(top = 24.dp),
        title = stringResource(Res.string.settings_config_export_section_title),
        applyHorizontalPadding = false,
    ) {
        if (onOpenConfigExport != null) {
            ListItem(
                headlineContent = {
                    Text(stringResource(Res.string.settings_config_export_export))
                },
                leadingContent = {
                    Icon(ADVANCED_CONFIG_EXPORT_ICON, contentDescription = null)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .radioConnectionHint(isDeviceConnected)
                    .clickable(enabled = isDeviceConnected, onClick = onOpenConfigExport),
            )
        }
        if (onOpenConfigImport != null) {
            ListItem(
                headlineContent = {
                    Text(stringResource(Res.string.settings_config_import_import_config))
                },
                leadingContent = {
                    Icon(ADVANCED_CONFIG_IMPORT_ICON, contentDescription = null)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .radioConnectionHint(isDeviceConnected)
                    .clickable(enabled = isDeviceConnected, onClick = onOpenConfigImport),
            )
        }
    }
    PreferenceHubFooter(
        stringResource(Res.string.settings_config_export_section_footer),
        applyHorizontalPadding = false,
    )
}

/** iOS `ConfigExportImportSection` export `square.and.arrow.up`. */
internal val ADVANCED_CONFIG_EXPORT_ICON get() = AppIcons.Share

/** iOS `ConfigExportImportSection` import `square.and.arrow.down`. */
internal val ADVANCED_CONFIG_IMPORT_ICON get() = AppIcons.CloudDownload
