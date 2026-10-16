package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactListState
import one.meshcore.shared.contact.NodeSegment
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.contact.publicKeyHex
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_list_empty_contacts_description
import one.meshcore.shared.resources.contacts_list_empty_contacts_title
import one.meshcore.shared.resources.contacts_list_empty_favorites_description
import one.meshcore.shared.resources.contacts_list_empty_favorites_title
import one.meshcore.shared.resources.contacts_list_empty_repeaters_description
import one.meshcore.shared.resources.contacts_list_empty_repeaters_title
import one.meshcore.shared.resources.contacts_list_empty_rooms_description
import one.meshcore.shared.resources.contacts_list_empty_rooms_title
import one.meshcore.shared.resources.contacts_list_empty_search_description
import one.meshcore.shared.resources.contacts_list_empty_search_title
import one.meshcore.shared.resources.contacts_segment_picker_label
import one.meshcore.shared.ui.AppSearchField
import one.meshcore.shared.ui.EmptyMessageColumn
import one.meshcore.shared.ui.ScrollingFilterChips
import one.meshcore.shared.ui.expressiveListItem
import one.meshcore.shared.ui.filterBarVisible
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.selectedRowHighlight
import org.jetbrains.compose.resources.stringResource

internal data class ContactListBodyState(
    val list: ContactListState,
    val filtered: List<ContactDto>,
    val searchPlaceholder: String,
    val isReady: Boolean,
    val nowSeconds: Long,
    val hashSize: Int,
    val userLocation: UserLocation?,
    val vContactKey: ByteArray?,
    val selectedContactId: String?,
)

internal data class ContactListBodyActions(
    val onSearchChange: (String) -> Unit,
    val onContactTap: (ContactDto) -> Unit,
    val onSendMessage: (ContactDto) -> Unit,
    val onToggleFavorite: (ContactDto) -> Unit,
    val onToggleBlocked: (ContactDto) -> Unit,
    val onDelete: (ContactDto) -> Unit,
    val onSelectSegment: (NodeSegment) -> Unit,
)

@Composable
internal fun ContactListBody(state: ContactListBodyState, actions: ContactListBodyActions) {
    Column(modifier = Modifier.fillMaxSize()) {
        AppSearchField(
            value = state.list.searchText,
            onValueChange = actions.onSearchChange,
            placeholder = state.searchPlaceholder,
        )
        ContactListSegmentPicker(
            selected = state.list.segment,
            isSearching = state.list.isSearching,
            onSelect = actions.onSelectSegment,
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxSize(),
            contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
        ) {
            when {
                !state.list.hasLoadedOnce -> item {
                    Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.filtered.isEmpty() -> item {
                    Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        ContactListEmptyState(
                            segment = state.list.segment,
                            searchText = state.list.searchText.takeIf { state.list.isSearching },
                        )
                    }
                }
                else -> items(state.filtered, key = { contact -> contact.id.value }) { contact ->
                    Box(
                        modifier = expressiveListItem().selectedRowHighlight(
                            contact.id.value == state.selectedContactId,
                        ),
                    ) {
                        ContactRow(
                            contact = contact,
                            showTypeLabel = state.list.isSearching,
                            hashSize = state.hashSize,
                            isReady = state.isReady,
                            isTogglingFavorite = state.list.togglingFavoriteId == contact.id,
                            isDeletePending = state.list.isDeletePending(contact.id),
                            inboundHopCount = state.list.inboundHopByKey[contact.publicKeyHex],
                            nowSeconds = state.nowSeconds,
                            userLocation = state.userLocation,
                            allowDelete = state.vContactKey == null ||
                                !contact.publicKey.contentEquals(state.vContactKey),
                            onTap = { actions.onContactTap(contact) },
                            onSendMessage = { actions.onSendMessage(contact) },
                            onToggleFavorite = { actions.onToggleFavorite(contact) },
                            onToggleBlocked = { actions.onToggleBlocked(contact) },
                            onDelete = { actions.onDelete(contact) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactListSegmentPicker(selected: NodeSegment, isSearching: Boolean, onSelect: (NodeSegment) -> Unit) {
    if (!filterBarVisible(isSearching)) return
    val pickerLabel = stringResource(Res.string.contacts_segment_picker_label)
    ScrollingFilterChips(
        entries = NodeSegment.entries,
        selected = selected,
        spokenLabel = pickerLabel,
        onSelect = onSelect,
    ) { it.label() }
}

/** A per-segment or search empty state. A non-null [searchText] always selects the search-empty copy. */
@Composable
private fun ContactListEmptyState(segment: NodeSegment, searchText: String?) {
    val icon: ImageVector
    val title: String
    val description: String
    if (searchText != null) {
        icon = AppIcons.Search
        title = stringResource(Res.string.contacts_list_empty_search_title)
        description = formattedStringResource(Res.string.contacts_list_empty_search_description, searchText)
    } else {
        when (segment) {
            NodeSegment.FAVORITES -> {
                icon = contactListEmptyIcon(segment)
                title = stringResource(Res.string.contacts_list_empty_favorites_title)
                description = stringResource(Res.string.contacts_list_empty_favorites_description)
            }
            NodeSegment.CONTACTS -> {
                icon = contactListEmptyIcon(segment)
                title = stringResource(Res.string.contacts_list_empty_contacts_title)
                description = stringResource(Res.string.contacts_list_empty_contacts_description)
            }
            NodeSegment.REPEATERS -> {
                icon = contactListEmptyIcon(segment)
                title = stringResource(Res.string.contacts_list_empty_repeaters_title)
                description = stringResource(Res.string.contacts_list_empty_repeaters_description)
            }
            NodeSegment.ROOMS -> {
                icon = contactListEmptyIcon(segment)
                title = stringResource(Res.string.contacts_list_empty_rooms_title)
                description = stringResource(Res.string.contacts_list_empty_rooms_description)
            }
        }
    }

    EmptyMessageColumn(
        title = title,
        icon = icon,
        description = description,
        modifier = Modifier.padding(24.dp),
    )
}

/** iOS `ContactsEmptyView` SF Symbols mapped to Material Symbols. */
internal fun contactListEmptyIcon(segment: NodeSegment): ImageVector = when (segment) {
    NodeSegment.FAVORITES -> AppIcons.Star
    NodeSegment.CONTACTS -> AppIcons.Group
    NodeSegment.REPEATERS -> AppIcons.CellTower
    NodeSegment.ROOMS -> AppIcons.MeetingRoom
}

/** Bottom list inset so the last row scrolls clear of the floating action button. */
private val FAB_CLEARANCE = 88.dp
