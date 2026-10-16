package one.meshcore.shared.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.NewChatViewModel
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.isFloodRouted
import one.meshcore.shared.contact.ui.ContactAvatar
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_connection_status_flood_routing
import one.meshcore.shared.resources.chats_new_chat_contact_type_direct
import one.meshcore.shared.resources.chats_new_chat_contact_type_repeater
import one.meshcore.shared.resources.chats_new_chat_contact_type_room
import one.meshcore.shared.resources.chats_new_chat_empty_state_description
import one.meshcore.shared.resources.chats_new_chat_empty_state_title
import one.meshcore.shared.resources.chats_new_chat_search_placeholder
import one.meshcore.shared.resources.chats_new_chat_title
import one.meshcore.shared.resources.contacts_list_empty_search_description
import one.meshcore.shared.resources.contacts_list_empty_search_title
import one.meshcore.shared.ui.AppSearchField
import one.meshcore.shared.ui.EmptyMessageColumn
import one.meshcore.shared.ui.ExpressiveSheetContent
import one.meshcore.shared.ui.expressiveListItem
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.modalSheetExpandedValues
import one.meshcore.shared.ui.pickerSheetSizeModifier
import org.jetbrains.compose.resources.stringResource

/**
 * The new-chat contact picker sheet (spec §12.1): a search field over the [viewModel]'s DM-capable
 * contacts, each row navigating to that DM via [onSelect]. Kotlin port of the iOS `NewChatView` sheet;
 * the empty state gates on the loaded contacts being empty (not the search result), matching iOS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatSheet(
    viewModel: NewChatViewModel,
    serviceOwner: ContactService?,
    radioId: Uuid?,
    onSelect: (ContactDto) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = modalSheetExpandedValues,
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val eligible = remember(state) { viewModel.eligibleContacts(state) }

    LaunchedEffect(viewModel, serviceOwner, radioId) { viewModel.load() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        ExpressiveSheetContent(modifier = pickerSheetSizeModifier(SHEET_HEIGHT_FRACTION)) {
            Text(
                text = stringResource(Res.string.chats_new_chat_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            AppSearchField(
                value = state.searchText,
                onValueChange = viewModel::setSearchText,
                placeholder = stringResource(Res.string.chats_new_chat_search_placeholder),
            )
            when {
                state.isLoading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.contacts.isEmpty() -> Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) { NewChatEmptyState() }

                eligible.isEmpty() -> Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    NewChatEmptyState(
                        title = stringResource(Res.string.contacts_list_empty_search_title),
                        description = formattedStringResource(
                            Res.string.contacts_list_empty_search_description,
                            state.searchText,
                        ),
                    )
                }

                else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(eligible, key = { it.id.value }) { contact ->
                        NewChatContactRow(
                            contact = contact,
                            onClick = { onSelect(contact) },
                            modifier = expressiveListItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewChatContactRow(contact: ContactDto, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = { Text(contact.displayName, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Text(
                contactTypeLabel(contact),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { ContactAvatar(contact = contact, size = ROW_AVATAR_SIZE) },
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
private fun NewChatEmptyState(
    title: String = stringResource(Res.string.chats_new_chat_empty_state_title),
    description: String = stringResource(Res.string.chats_new_chat_empty_state_description),
) {
    EmptyMessageColumn(
        title = title,
        icon = NEW_CHAT_EMPTY_ICON,
        description = description,
        modifier = Modifier.padding(24.dp),
    )
}

/**
 * The row's type label, mirroring iOS `NewChatView.contactTypeLabel`: a chat contact reads "Flood routing"
 * when flood-routed else "Direct"; repeaters and rooms are filtered out of the picker but keep their labels
 * for completeness.
 */
@Composable
private fun contactTypeLabel(contact: ContactDto): String = when (contact.contactType) {
    ContactType.CHAT -> if (contact.isFloodRouted) {
        stringResource(Res.string.chats_connection_status_flood_routing)
    } else {
        stringResource(Res.string.chats_new_chat_contact_type_direct)
    }

    ContactType.REPEATER -> stringResource(Res.string.chats_new_chat_contact_type_repeater)
    ContactType.ROOM -> stringResource(Res.string.chats_new_chat_contact_type_room)
}

private val ROW_AVATAR_SIZE = 40.dp
private const val SHEET_HEIGHT_FRACTION = 0.9f

/** iOS `NewChatView` empty: `person.2`. */
internal val NEW_CHAT_EMPTY_ICON get() = AppIcons.Group
