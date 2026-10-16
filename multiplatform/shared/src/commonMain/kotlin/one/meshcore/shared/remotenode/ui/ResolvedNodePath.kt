package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.contact.pathHopCount
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_detail_route_direct
import one.meshcore.shared.resources.contacts_detail_route_prefix
import one.meshcore.shared.resources.contacts_route_direct
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_no_route_set
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_path_hop_unknown
import org.jetbrains.compose.resources.stringResource

internal data class ResolvedNodePath(
    val summary: String,
    val hops: List<NodeRoutePathLogic.ResolvedPathHop>,
    val summarySpoken: String,
)

/** Flood, direct, or hop-chain summary plus the resolved hop rows for [contact]. */
@Composable
internal fun rememberResolvedNodePath(
    contact: ContactDto,
    contacts: List<ContactDto>,
    discovered: List<DiscoveredNodeRecord>,
    userLocation: UserLocation?,
): ResolvedNodePath {
    val floodLabel = stringResource(Res.string.remotenodes_remote_nodes_auth_no_route_set)
    val directLabel = stringResource(Res.string.contacts_route_direct)
    val unknownHop = stringResource(Res.string.remotenodes_remote_nodes_auth_path_hop_unknown)
    val summary = NodeRoutePathLogic.summaryText(contact, floodLabel, directLabel)
    val hops = remember(contact, contacts, discovered, userLocation, unknownHop) {
        NodeRoutePathLogic.resolveHops(contact, contacts, unknownHop, discovered, userLocation)
    }
    val summarySpoken = nodePathSummaryAccessibilityLabel(
        pathHopCount = contact.pathHopCount,
        pathDisplay = summary,
        directLabel = stringResource(Res.string.contacts_detail_route_direct),
        prefixTemplate = stringResource(Res.string.contacts_detail_route_prefix),
    )
    return ResolvedNodePath(summary, hops, summarySpoken)
}

/** One resolved hop: monospace prefix, repeater name, and the possible-match marker. */
@Composable
internal fun ResolvedPathHopLine(
    hop: NodeRoutePathLogic.ResolvedPathHop,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(RESOLVED_HOP_LINE_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = hop.hex,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(hop.resolution.displayName, style = MaterialTheme.typography.bodyMedium)
        if (hop.resolution.matchKind == NodeNameMatchKind.FALLBACK) {
            PossibleMatchMarker()
        }
    }
}

private val RESOLVED_HOP_LINE_SPACING = 6.dp
