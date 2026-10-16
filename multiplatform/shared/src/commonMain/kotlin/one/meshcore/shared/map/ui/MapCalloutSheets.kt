package one.meshcore.shared.map.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.idPrefixHex
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.formatCoordinatePair
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_detail_open_in_maps
import one.meshcore.shared.resources.contacts_trace_map_clear
import one.meshcore.shared.resources.map_callout_details
import one.meshcore.shared.resources.map_callout_message
import one.meshcore.shared.resources.map_callout_node_kind_contact
import one.meshcore.shared.resources.map_callout_node_kind_repeater
import one.meshcore.shared.resources.map_callout_node_kind_room
import one.meshcore.shared.resources.tools_line_of_sight_copy_coordinates
import one.meshcore.shared.resources.tools_line_of_sight_dropped_pin
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McActionSheet
import one.meshcore.shared.ui.menu.McActionSheetDivider
import one.meshcore.shared.ui.menu.McActionSheetHeader
import one.meshcore.shared.ui.menu.McActionSheetItem
import one.meshcore.shared.ui.menu.McMenuRole
import org.jetbrains.compose.resources.stringResource

/** Localized node-kind label for a contact, shared by the map list rows and the contact callout sheet. */
@Composable
private fun kindLabel(contactType: ContactType): String = when (contactType) {
    ContactType.CHAT -> stringResource(Res.string.map_callout_node_kind_contact)
    ContactType.REPEATER -> stringResource(Res.string.map_callout_node_kind_repeater)
    ContactType.ROOM -> stringResource(Res.string.map_callout_node_kind_room)
}

@Composable
internal fun ContactCalloutSheet(
    contact: ContactDto,
    hashSize: Int,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onMessage: () -> Unit,
) {
    val kindLabel = kindLabel(contact.contactType)
    // Details and Message actions use the contact name as their accessibility hint.
    val actionHint = contact.displayName
    McActionSheet(
        onDismiss = onDismiss,
        title = contact.displayName,
    ) {
        McActionSheetHeader {
            Text(
                text = kindLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    // Speak the name; the id-prefix hex is visual.
                    contentDescription = contact.displayName
                },
            )
            Text(
                text = contact.idPrefixHex(hashSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        if (contact.contactType == ContactType.CHAT) {
            McActionSheetItem(
                text = stringResource(Res.string.map_callout_message),
                leadingIcon = AppIcons.Chat,
                stateDescription = actionHint,
                onClick = {
                    onMessage()
                    onDismiss()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DroppedPinCalloutSheet(
    coordinate: LatLng,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onOpenInMaps: () -> Unit,
    onClear: () -> Unit,
) {
    val coords = formatCoordinatePair(coordinate.latitude, coordinate.longitude)
    McActionSheet(
        onDismiss = onDismiss,
        title = stringResource(Res.string.tools_line_of_sight_dropped_pin),
    ) {
        McActionSheetHeader {
            Text(
                text = coords,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        McActionSheetItem(
            text = stringResource(Res.string.tools_line_of_sight_copy_coordinates),
            leadingIcon = AppIcons.ContentCopy,
            onClick = {
                onCopy()
                onDismiss()
            },
        )
        McActionSheetItem(
            text = stringResource(Res.string.contacts_detail_open_in_maps),
            leadingIcon = AppIcons.Map,
            onClick = {
                onOpenInMaps()
                onDismiss()
            },
        )
        McActionSheetDivider()
        McActionSheetItem(
            text = stringResource(Res.string.contacts_trace_map_clear),
            leadingIcon = AppIcons.Delete,
            role = McMenuRole.Destructive,
            onClick = {
                onClear()
                onDismiss()
            },
        )
    }
}
