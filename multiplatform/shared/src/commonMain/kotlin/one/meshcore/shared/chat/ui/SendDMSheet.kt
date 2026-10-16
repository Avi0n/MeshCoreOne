package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.SendDMViewModel
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_sender_unverified_nickname_format
import one.meshcore.shared.resources.chats_send_dm_limitation
import one.meshcore.shared.resources.chats_send_dm_matching_contacts
import one.meshcore.shared.resources.chats_send_dm_no_matches
import one.meshcore.shared.resources.chats_send_dm_title
import one.meshcore.shared.ui.EmptyMessageColumn
import one.meshcore.shared.ui.ExpressiveSheetContent
import one.meshcore.shared.ui.expressiveListItem
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.modalSheetExpandedValues
import one.meshcore.shared.ui.pickerSheetSizeModifier
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The "send DM to a channel sender" picker sheet (spec §12.2): the [viewModel] resolves [senderName] to the
 * name-matching contacts (blocked excluded) and this presents them, each row opening that DM via [onSelect].
 * Kotlin port of the iOS `SendDMSheet`. The title mirrors the bubble's unverified-nickname presentation —
 * the [unverifiedNickname] prominent with the raw [senderName] parenthesized — because a channel sender is
 * not itself a verified identity. No matches shows the "no matches" state.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
fun SendDMSheet(
    viewModel: SendDMViewModel,
    serviceOwner: ContactService?,
    senderName: String,
    radioID: Uuid,
    unverifiedNickname: String?,
    onSelect: (ContactDto) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    userLocation: UserLocation? = null,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = modalSheetExpandedValues,
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    // One timestamp anchor for the candidates' last-advert labels; live ticking is not needed in a sheet.
    val nowSeconds = remember { Clock.System.now().epochSeconds }

    LaunchedEffect(viewModel, serviceOwner, senderName, radioID) { viewModel.load(senderName, radioID) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        ExpressiveSheetContent(modifier = pickerSheetSizeModifier(SHEET_HEIGHT_FRACTION)) {
            Text(
                text = formattedStringResource(
                    Res.string.chats_send_dm_title,
                    titleName(senderName, unverifiedNickname),
                ),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            when {
                state.isLoading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.matchingContacts.isEmpty() -> Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) { NoMatchesState(senderName) }

                else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    item { SendDMHeader() }
                    items(state.matchingContacts, key = { it.id.value }) { contact ->
                        ContactMatchRow(
                            contact = contact,
                            selection = ContactMatchSelection.Tap,
                            userLocation = userLocation,
                            nowSeconds = nowSeconds,
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
private fun SendDMHeader() {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(Res.string.chats_send_dm_limitation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(Res.string.chats_send_dm_matching_contacts),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoMatchesState(senderName: String) {
    EmptyMessageColumn(
        title = formattedStringResource(Res.string.chats_send_dm_no_matches, senderName),
        icon = SEND_DM_NO_MATCHES_ICON,
        modifier = Modifier.padding(24.dp),
    )
}

/**
 * The title's name: the unverified nickname prominent with the raw sender parenthesized (via the
 * `(%s)` format string), matching the bubble's unverified-nickname presentation; the raw name alone when
 * no nickname is known. Mirrors iOS `SendDMSheet.titleName`.
 */
@Composable
private fun titleName(senderName: String, unverifiedNickname: String?): String {
    if (unverifiedNickname == null) return senderName
    val parenthesized = formattedStringResource(Res.string.chats_message_sender_unverified_nickname_format, senderName)
    return "$unverifiedNickname $parenthesized"
}

private const val SHEET_HEIGHT_FRACTION = 0.9f
