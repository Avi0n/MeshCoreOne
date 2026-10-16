package one.meshcore.shared.map.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import one.meshcore.shared.contact.DEFAULT_HASH_SIZE
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.hasLocation
import one.meshcore.shared.contact.idPrefixHex
import one.meshcore.shared.contact.relativeAgeLabel
import one.meshcore.shared.contact.ui.kindLabel
import one.meshcore.shared.map.formatFixed6
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.map_callout_add
import one.meshcore.shared.resources.map_callout_details
import one.meshcore.shared.resources.map_callout_discovered
import one.meshcore.shared.resources.map_detail_last_advert
import one.meshcore.shared.resources.map_detail_latitude
import one.meshcore.shared.resources.map_detail_longitude
import one.meshcore.shared.resources.map_detail_name
import one.meshcore.shared.resources.map_detail_public_key
import one.meshcore.shared.resources.map_detail_section_contact_info
import one.meshcore.shared.resources.map_detail_section_location
import one.meshcore.shared.resources.map_detail_type
import one.meshcore.shared.resources.map_discovered_detail_add
import one.meshcore.shared.resources.map_discovered_detail_title
import one.meshcore.shared.resources.map_pin_accessibility_discovered
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McActionSheet
import one.meshcore.shared.ui.menu.McActionSheetHeader
import one.meshcore.shared.ui.menu.McActionSheetItem
import one.meshcore.shared.ui.preferenceHubFormScroll
import org.jetbrains.compose.resources.stringResource

/**
 * Bottom-sheet callout for a discovered (not-yet-added) map pin. Mirrors iOS
 * `DiscoveredNodeCalloutContent`: id prefix + name, kind + "Discovered", then Details and Add. Add is
 * disabled while an add is in flight.
 */
@Composable
fun DiscoveredNodeCalloutSheet(
    node: DiscoveredNodeRecord,
    isAdding: Boolean,
    hashSize: Int = DEFAULT_HASH_SIZE,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onAdd: () -> Unit,
) {
    val discoveredLabel = stringResource(Res.string.map_callout_discovered)
    val discoveredA11y = stringResource(Res.string.map_pin_accessibility_discovered)
    val kind = node.contactType.kindLabel()
    // Actions use the discovered node name as their accessibility hint.
    val actionHint = node.name
    McActionSheet(
        onDismiss = onDismiss,
        title = node.name,
    ) {
        McActionSheetHeader {
            Text(
                text = "$kind, $discoveredLabel",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = discoveredCalloutHeadlineContentDescription(
                        discoveredLabel = discoveredLabel,
                        name = node.name,
                    )
                },
            )
            Text(
                text = node.idPrefixHex(hashSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = "$kind, $discoveredA11y"
                },
            )
        }
        McActionSheetItem(
            text = stringResource(Res.string.map_callout_details),
            leadingIcon = AppIcons.Info,
            stateDescription = actionHint,
            onClick = {
                onDetails()
                onDismiss()
            },
        )
        McActionSheetItem(
            text = stringResource(Res.string.map_callout_add),
            leadingIcon = AppIcons.PersonAdd,
            stateDescription = actionHint,
            enabled = !isAdding,
            onClick = {
                onAdd()
                onDismiss()
            },
        )
    }
}

/**
 * Read-only discovered-node details, with Add to Nodes. Hosted as a nested destination, matching
 * saved-contact map details. Name, type, last advert, public key, and location when the row carries
 * a coordinate.
 */
@Composable
fun DiscoveredNodeDetailSheet(
    node: DiscoveredNodeRecord,
    isAdding: Boolean,
    nowEpochSeconds: Long,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
) {
    FullScreenOverlay(
        onDismiss = onDismiss,
        title = stringResource(Res.string.map_discovered_detail_title),
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = PreferenceHubHorizontalPadding, vertical = 8.dp),
        ) {
            Column(
                preferenceHubFormScroll(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PreferenceHubSection(
                    title = stringResource(Res.string.map_detail_section_contact_info),
                    applyHorizontalPadding = false,
                ) {
                    PreferenceHubValueRow(stringResource(Res.string.map_detail_name), node.name)
                    PreferenceHubValueRow(stringResource(Res.string.map_detail_type), node.contactType.kindLabel())
                    PreferenceHubValueRow(
                        stringResource(Res.string.map_callout_discovered),
                        stringResource(Res.string.map_pin_accessibility_discovered),
                    )
                    PreferenceHubValueRow(
                        stringResource(Res.string.map_detail_last_advert),
                        relativeAgeLabel(node.lastHeardEpochSeconds, nowEpochSeconds),
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.map_detail_public_key)) },
                        supportingContent = {
                            Text(
                                text = node.publicKey.toUppercaseHex(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (node.hasLocation) {
                    PreferenceHubSection(
                        title = stringResource(Res.string.map_detail_section_location),
                        applyHorizontalPadding = false,
                    ) {
                        PreferenceHubValueRow(
                            stringResource(Res.string.map_detail_latitude),
                            formatFixed6(node.latitude),
                        )
                        PreferenceHubValueRow(
                            stringResource(Res.string.map_detail_longitude),
                            formatFixed6(node.longitude),
                        )
                    }
                }
            }
            Button(
                onClick = onAdd,
                enabled = !isAdding,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.map_discovered_detail_add))
            }
        }
    }
}
