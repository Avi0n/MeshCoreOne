package one.meshcore.shared.contact.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.contact.ContactListError
import one.meshcore.shared.contact.ContactRouteKind
import one.meshcore.shared.contact.NodeSegment
import one.meshcore.shared.contact.NodeSortOrder
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_error
import one.meshcore.shared.resources.contacts_list_cannot_refresh
import one.meshcore.shared.resources.contacts_list_connect_to_sync
import one.meshcore.shared.resources.contacts_node_kind_chat
import one.meshcore.shared.resources.contacts_node_kind_repeater
import one.meshcore.shared.resources.contacts_node_kind_room
import one.meshcore.shared.resources.contacts_route_direct
import one.meshcore.shared.resources.contacts_route_flood
import one.meshcore.shared.resources.contacts_route_hops
import one.meshcore.shared.resources.contacts_segment_contacts
import one.meshcore.shared.resources.contacts_segment_favorites
import one.meshcore.shared.resources.contacts_segment_repeaters
import one.meshcore.shared.resources.contacts_segment_rooms
import one.meshcore.shared.resources.contacts_sort_distance
import one.meshcore.shared.resources.contacts_sort_hops
import one.meshcore.shared.resources.contacts_sort_last_heard
import one.meshcore.shared.resources.contacts_sort_name
import one.meshcore.shared.resources.contacts_view_model_connect_to_delete
import one.meshcore.shared.resources.contacts_view_model_remove_timed_out
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The localized picker title for a Nodes segment. */
@Composable
fun NodeSegment.label(): String = stringResource(
    when (this) {
        NodeSegment.FAVORITES -> Res.string.contacts_segment_favorites
        NodeSegment.CONTACTS -> Res.string.contacts_segment_contacts
        NodeSegment.REPEATERS -> Res.string.contacts_segment_repeaters
        NodeSegment.ROOMS -> Res.string.contacts_segment_rooms
    },
)

/** The localized menu title for a Nodes sort order. */
@Composable
fun NodeSortOrder.label(): String = stringResource(
    when (this) {
        NodeSortOrder.LAST_HEARD -> Res.string.contacts_sort_last_heard
        NodeSortOrder.NAME -> Res.string.contacts_sort_name
        NodeSortOrder.DISTANCE -> Res.string.contacts_sort_distance
        NodeSortOrder.HOPS -> Res.string.contacts_sort_hops
    },
)

/** The localized route label for a row: "Direct", "N hops", or "Flood". */
@Composable
fun ContactRouteKind.label(): String = when (this) {
    ContactRouteKind.Direct -> stringResource(Res.string.contacts_route_direct)
    is ContactRouteKind.Hops -> formattedStringResource(Res.string.contacts_route_hops, count)
    ContactRouteKind.Flood -> stringResource(Res.string.contacts_route_flood)
}

/** The localized node-kind label shown beside a row in search results. */
@Composable
fun ContactType.kindLabel(): String = stringResource(
    when (this) {
        ContactType.CHAT -> Res.string.contacts_node_kind_chat
        ContactType.REPEATER -> Res.string.contacts_node_kind_repeater
        ContactType.ROOM -> Res.string.contacts_node_kind_room
    },
)

/**
 * The alert title for a surfaced error. An offline refresh gets its own "Cannot Refresh" heading and
 * carries the reason in the body, as on iOS; everything else falls under the generic error heading.
 * Pure so the pairing with [messageResource] is asserted without a composition.
 */
fun ContactListError.titleResource(): StringResource = when (this) {
    ContactListError.CannotRefresh -> Res.string.contacts_list_cannot_refresh
    else -> Res.string.contacts_common_error
}

/**
 * The localized body for a surfaced error, or `null` for [ContactListError.ServiceMessage], whose text
 * comes from the lower layer rather than from a resource.
 */
fun ContactListError.messageResource(): StringResource? = when (this) {
    ContactListError.ConnectToDelete -> Res.string.contacts_view_model_connect_to_delete
    ContactListError.RemoveTimedOut -> Res.string.contacts_view_model_remove_timed_out
    ContactListError.CannotRefresh -> Res.string.contacts_list_connect_to_sync
    is ContactListError.ServiceMessage -> null
}

/** The user-facing alert title for a surfaced error. */
@Composable
fun ContactListError.title(): String = stringResource(titleResource())

/**
 * The user-facing message for a surfaced error. The typed cases resolve to localized resources; a
 * [ContactListError.ServiceMessage] carries the lower layer's already-English text verbatim.
 */
@Composable
fun ContactListError.message(): String {
    if (this is ContactListError.ServiceMessage) return message
    // Every remaining case is exhaustive in messageResource, so the resource is always present here.
    return stringResource(checkNotNull(messageResource()))
}
