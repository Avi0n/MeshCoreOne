package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.PathAccessibilityCopy
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.contact.isFloodRouted
import one.meshcore.shared.contact.pathAccessibilityLabel
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_path
import one.meshcore.shared.ui.PathHopChipRow
import one.meshcore.shared.ui.PreferenceHubCardPadding
import org.jetbrains.compose.resources.stringResource

/**
 * Read-only display of the route used to reach a node. Mirrors iOS `NodeRoutePathSection`.
 */
@Composable
fun NodeRoutePathSection(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    contact: ContactDto,
    contacts: List<ContactDto>,
    discovered: List<DiscoveredNodeRecord>,
    userLocation: UserLocation?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        StatusSection(
            title = stringResource(Res.string.remotenodes_remote_nodes_auth_path),
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            isLoading = false,
        ) {
            val path = rememberResolvedNodePath(contact, contacts, discovered, userLocation)
            Column(
                Modifier.padding(PreferenceHubCardPadding),
                verticalArrangement = Arrangement.spacedBy(LABEL_SPACING),
            ) {
                if (contact.isFloodRouted) {
                    Text(
                        text = path.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (path.hops.isEmpty()) {
                    Text(
                        text = path.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { contentDescription = path.summarySpoken },
                    )
                } else {
                    PathHopChipRow(
                        hops = path.hops.map { it.hex },
                        modifier = Modifier.semantics { contentDescription = path.summarySpoken },
                    )
                    path.hops.forEach { hop ->
                        ResolvedPathHopLine(hop)
                    }
                }
            }
        }
    }
}

private val LABEL_SPACING = 6.dp

/** iOS `NodePathSummaryLabel` VoiceOver: “Route: Direct” or “Route: {hops}”. */
internal fun nodePathSummaryAccessibilityLabel(
    pathHopCount: Int,
    pathDisplay: String,
    directLabel: String,
    prefixTemplate: String,
): String = pathAccessibilityLabel(
    isFloodRouted = false,
    pathHopCount = pathHopCount,
    pathDisplay = pathDisplay,
    copy = PathAccessibilityCopy(
        floodLabel = "",
        directLabel = directLabel,
        prefixTemplate = prefixTemplate,
    ),
)
