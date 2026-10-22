// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomMessageActionsSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomMessageDTO+Status.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/SendDMSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as Chats
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.chats.actions.MessageAction
import com.meshcoreone.android.feature.chats.list.SenderContactMatcher
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomMessageActionsSheet(
    message: RoomMessageDTO,
    availability: RoomMessageActionAvailability,
    replyWithQuote: Boolean,
    onAction: (MessageAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var dispatching by remember(message.id) { mutableStateOf(false) }
    val resources = LocalResources.current
    val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(message.authorDisplayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(message.text)
            HorizontalDivider()
            availability.actions.forEach { action ->
                val label = when (action) {
                    MessageAction.Reply -> if (replyWithQuote) Chats.chatsMessageActionReply else Chats.chatsMessageActionMention
                    MessageAction.SendDirectMessage -> Chats.chatsMessageActionSendDM
                    MessageAction.Copy -> Chats.chatsMessageActionCopy
                    MessageAction.SendAgain -> Chats.chatsMessageActionSendAgain
                    else -> error("Unsupported room message action: $action")
                }
                TextButton(
                    onClick = {
                        dispatching = true
                        scope.launch {
                            sheet.hide()
                            onAction(action)
                        }
                    },
                    enabled = !dispatching,
                    modifier = Modifier.fillMaxWidth().sharedTouchTarget(),
                ) { Text(stringResource(label), Modifier.fillMaxWidth()) }
            }
            HorizontalDivider()
            Text(stringResource(Chats.chatsMessageActionDetails), style = MaterialTheme.typography.labelLarge)
            Text(Chats.chatsMessageInfoSent(resources, dateFormat.format(message.date)))
            if (!message.isFromSelf) {
                Text(Chats.chatsMessageInfoReceived(resources, dateFormat.format(message.createdAt)))
            }
            Text(stringResource(when (message.status) {
                MessageStatus.PENDING, MessageStatus.SENDING -> Chats.chatsMessageStatusSending
                MessageStatus.SENT -> Chats.chatsMessageStatusSent
                MessageStatus.DELIVERED -> Chats.chatsMessageStatusDelivered
                MessageStatus.FAILED -> Chats.chatsMessageStatusFailed
                MessageStatus.RETRYING -> Chats.chatsMessageStatusRetrying
            }))
            if (message.isFromSelf) message.roundTripTime?.let {
                Text(Chats.chatsMessageInfoRoundTrip(resources, it.toInt()))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomSendDirectMessageSheet(
    senderName: String,
    radioId: RadioId,
    contacts: List<ContactDTO>,
    onSelect: (ContactDTO) -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    val matching = SenderContactMatcher.filter(
        contacts.filter { it.radioId == radioId }, senderName, excludeBlocked = true,
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(Chats.chatsSendDMTitle(resources, senderName), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            if (matching.isEmpty()) Text(Chats.chatsSendDMNoMatches(resources, senderName))
            else {
                Text(stringResource(Chats.chatsSendDMLimitation))
                Text(stringResource(Chats.chatsSendDMMatchingContacts))
                matching.forEach { contact ->
                    TextButton({ onSelect(contact) }, Modifier.fillMaxWidth().sharedTouchTarget()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(contact.displayName)
                            Text(contact.publicKey.hexString, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            TextButton(onDismiss, Modifier.sharedTouchTarget()) { Text(stringResource(Chats.chatsSendDMCancel)) }
        }
    }
}
