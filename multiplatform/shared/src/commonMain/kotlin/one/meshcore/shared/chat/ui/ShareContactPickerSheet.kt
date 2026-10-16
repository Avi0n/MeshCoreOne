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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ChatShareLogic
import one.meshcore.shared.chat.ShareContactPickerViewModel
import one.meshcore.shared.chat.sharePickerTitle
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.ui.ContactAvatar
import one.meshcore.shared.contact.ui.kindLabel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_contact_picker_empty_state
import one.meshcore.shared.resources.chats_contact_picker_search_placeholder
import one.meshcore.shared.resources.chats_contact_picker_title
import one.meshcore.shared.resources.chats_error_load_conversations_failed
import one.meshcore.shared.settings.PersistentLogger
import one.meshcore.shared.ui.AppSearchField
import one.meshcore.shared.ui.EmptyMessageColumn
import one.meshcore.shared.ui.ExpressiveSheetContent
import one.meshcore.shared.ui.expressiveListItem
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.modalSheetExpandedValues
import one.meshcore.shared.ui.pickerSheetSizeModifier
import org.jetbrains.compose.resources.stringResource

/**
 * Picker that formats a selected contact as a MeshCore share token and hands it to [onInsert].
 * Mirrors iOS `ShareContactPickerSheet`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareContactPickerSheet(
    loadContacts: suspend () -> List<ContactDto>,
    serviceOwner: ContactService?,
    radioId: Uuid?,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hashSize: Int = 1,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = modalSheetExpandedValues,
    )
    val scope = rememberCoroutineScope()
    val logger = remember(scope) { PersistentLogger(scope, category = "ShareContactPicker") }
    val loadFailedMessage = stringResource(Res.string.chats_error_load_conversations_failed)
    val currentLoadFailedMessage by rememberUpdatedState(loadFailedMessage)
    val currentLoadContacts by rememberUpdatedState(loadContacts)
    val viewModel = remember {
        ShareContactPickerViewModel(
            loadFailedMessage = { currentLoadFailedMessage },
            onLoadError = { logger.warning("Contact picker load failed: ${it.message ?: it}") },
            loadContacts = { currentLoadContacts() },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var searchText by remember { mutableStateOf("") }

    LaunchedEffect(viewModel, serviceOwner, radioId) { viewModel.load() }

    val filtered = remember(state.contacts, searchText) {
        ChatShareLogic.filterShareContacts(state.contacts, searchText)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        ExpressiveSheetContent(modifier = pickerSheetSizeModifier(SHEET_HEIGHT_FRACTION)) {
            Text(
                text = stringResource(Res.string.chats_contact_picker_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            AppSearchField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = stringResource(Res.string.chats_contact_picker_search_placeholder),
            )
            when {
                state.isLoading -> Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                filtered.isEmpty() -> Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyMessageColumn(
                        title = stringResource(Res.string.chats_contact_picker_empty_state),
                        icon = SHARE_CONTACT_EMPTY_ICON,
                        modifier = Modifier.padding(24.dp),
                        titleColor = MaterialTheme.colorScheme.onSurface,
                    )
                }

                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(filtered, key = { it.id.value }) { contact ->
                        ShareContactRow(
                            contact = contact,
                            hashSize = hashSize,
                            onClick = {
                                onInsert(ChatShareLogic.formatContactToken(contact))
                                onDismiss()
                            },
                            modifier = expressiveListItem(),
                        )
                    }
                }
            }
        }
        state.errorMessage?.let { message ->
            ThreadErrorDialog(message = message, onDismiss = viewModel::dismissError)
        }
    }
}

@Composable
private fun ShareContactRow(contact: ContactDto, hashSize: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = {
            Text(contact.sharePickerTitle(hashSize), fontWeight = FontWeight.Medium)
        },
        supportingContent = {
            Text(
                contact.contactType.kindLabel(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { ContactAvatar(contact = contact, size = ROW_AVATAR_SIZE) },
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                // Speak only the name, not the hex prefix or type caption.
                contentDescription = contact.displayName
            },
    )
}

private const val SHEET_HEIGHT_FRACTION = 0.85f
private val ROW_AVATAR_SIZE = 40.dp

/** iOS `ShareContactPickerSheet` empty: `person.2`. */
internal val SHARE_CONTACT_EMPTY_ICON get() = AppIcons.Group
