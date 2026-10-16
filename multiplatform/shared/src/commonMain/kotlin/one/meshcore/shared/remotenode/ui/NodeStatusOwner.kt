package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_firmware
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_accessibility_reload_owner_info
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_no_owner_info
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_owner_info
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_owner_info_footer
import one.meshcore.shared.ui.PreferenceHubStatusRow
import one.meshcore.shared.ui.PreferenceHubValueRow
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun OwnerInfoSection(viewModel: NodeStatusViewModel, state: NodeStatusState, isRadioReady: Boolean) {
    StatusSection(
        expanded = state.ownerInfoExpanded,
        onExpandedChange = viewModel::setOwnerInfoExpanded,
        title = stringResource(Res.string.remotenodes_remote_nodes_status_owner_info),
        isLoading = state.isLoadingOwnerInfo,
        onReload = { viewModel.requestOwnerInfo() },
        reloadAccessibilityLabel = stringResource(
            Res.string.remotenodes_remote_nodes_status_accessibility_reload_owner_info,
        ),
        isRadioReady = isRadioReady,
        footer = stringResource(Res.string.remotenodes_remote_nodes_status_owner_info_footer),
    ) {
        val ownerInfo = state.ownerInfo
        when {
            state.ownerInfoSectionError != null -> SectionErrorText(state.ownerInfoSectionError)
            !ownerInfo.isNullOrEmpty() -> ListItem(
                headlineContent = {
                    Text(ownerInfo, style = MaterialTheme.typography.bodyMedium)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            )
            state.ownerInfoLoaded -> PreferenceHubStatusRow(
                stringResource(Res.string.remotenodes_remote_nodes_status_no_owner_info),
            )
        }
        // Firmware is a sibling of owner_info in the wire response, so it can be present even when the
        // owner text is empty; keep it out of the owner-text branch.
        if (state.ownerInfoSectionError == null) {
            state.firmwareVersion?.let { firmware ->
                PreferenceHubValueRow(stringResource(Res.string.remotenodes_remote_nodes_settings_firmware), firmware)
            }
        }
    }
}
