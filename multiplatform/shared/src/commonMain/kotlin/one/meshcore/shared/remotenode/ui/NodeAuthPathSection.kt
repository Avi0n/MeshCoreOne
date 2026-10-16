package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_flood_footer
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_flood_routing
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_no_route_footer
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_no_route_set
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_path
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_path_footer
import one.meshcore.shared.ui.PathHopChipRow
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PathSection(
    state: NodeAuthState,
    contact: ContactDto,
    hasStoredPath: Boolean,
    viewModel: NodeAuthViewModel,
) {
    PreferenceHubSection(
        title = stringResource(Res.string.remotenodes_remote_nodes_auth_path),
        applyHorizontalPadding = false,
    ) {
        when {
            !hasStoredPath -> ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(Res.string.remotenodes_remote_nodes_auth_no_route_set),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            )
            !state.useFloodRouting -> ResolvedPathPreview(
                contact = contact,
                contacts = state.pathContacts,
                discovered = state.pathDiscovered,
                userLocation = state.pathUserLocation,
            )
        }
        val noRouteFooter = stringResource(Res.string.remotenodes_remote_nodes_auth_no_route_footer)
        ToggleRow(
            label = stringResource(Res.string.remotenodes_remote_nodes_auth_flood_routing),
            checked = state.useFloodRouting,
            onCheckedChange = viewModel::setUseFloodRouting,
            enabled = hasStoredPath,
            disabledHint = if (hasStoredPath) null else noRouteFooter,
        )
    }
    PreferenceHubFooter(
        text = stringResource(
            when {
                !hasStoredPath -> Res.string.remotenodes_remote_nodes_auth_no_route_footer
                state.useFloodRouting -> Res.string.remotenodes_remote_nodes_auth_flood_footer
                else -> Res.string.remotenodes_remote_nodes_auth_path_footer
            },
        ),
        applyHorizontalPadding = false,
    )
}

/**
 * The stored route's hop chain, each hop's hex resolved to a repeater name (or the unknown-hop label), shown
 * as a collapsed summary that expands to per-hop rows. Mirrors the iOS auth-sheet path disclosure. A direct
 * or unresolved route collapses to a single summary line.
 */
@Composable
private fun ResolvedPathPreview(
    contact: ContactDto,
    contacts: List<ContactDto>,
    discovered: List<DiscoveredNodeRecord>,
    userLocation: UserLocation?,
) {
    val path = rememberResolvedNodePath(contact, contacts, discovered, userLocation)
    if (path.hops.isEmpty()) {
        Text(
            text = path.summary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(horizontal = PreferenceHubCardPadding, vertical = 8.dp)
                .semantics { contentDescription = path.summarySpoken },
        )
        return
    }
    PathHopChipRow(
        hops = path.hops.map { it.hex },
        modifier = Modifier
            .padding(horizontal = PreferenceHubCardPadding, vertical = 8.dp)
            .semantics { contentDescription = path.summarySpoken },
    )
    path.hops.forEach { hop ->
        ResolvedPathHopLine(
            hop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PreferenceHubCardPadding, vertical = 4.dp),
        )
    }
}
