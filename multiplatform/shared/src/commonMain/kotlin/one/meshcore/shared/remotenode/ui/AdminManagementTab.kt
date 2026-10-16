package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_tab_cli
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_tab_picker
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_tab_settings
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_tab_telemetry
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Settings, CLI, then telemetry. Repeater and room admin pages share this order. */
internal enum class AdminManagementTab { SETTINGS, CLI, TELEMETRY }

internal fun AdminManagementTab.labelResource(): StringResource = when (this) {
    AdminManagementTab.SETTINGS -> Res.string.remotenodes_remote_nodes_settings_tab_settings
    AdminManagementTab.CLI -> Res.string.remotenodes_remote_nodes_settings_tab_cli
    AdminManagementTab.TELEMETRY -> Res.string.remotenodes_remote_nodes_settings_tab_telemetry
}

@Composable
internal fun AdminManagementTabRow(selected: AdminManagementTab, onSelect: (AdminManagementTab) -> Unit) {
    val picker = stringResource(Res.string.remotenodes_remote_nodes_settings_tab_picker)
    val selectedIndex = AdminManagementTab.entries.indexOf(selected)
    SecondaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = picker },
    ) {
        AdminManagementTab.entries.forEach { entry ->
            Tab(
                selected = selected == entry,
                onClick = { onSelect(entry) },
                text = {
                    Text(
                        stringResource(entry.labelResource()),
                        maxLines = 1,
                    )
                },
            )
        }
    }
}
