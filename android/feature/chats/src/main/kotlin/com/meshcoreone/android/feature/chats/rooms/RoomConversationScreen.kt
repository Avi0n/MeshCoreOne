// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomConversationView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Room/RoomAuthenticationSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomMessageBubble.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomMessageDTO+Status.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.rooms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.designsystem.AppColors
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.designsystem.WCAGContrast
import com.meshcoreone.android.core.designsystem.accessibleForeground
import com.meshcoreone.android.core.designsystem.toComposeColor
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.chats.composer.MentionPickerContext
import com.meshcoreone.android.feature.chats.composer.MentionTapEvaluator
import com.meshcoreone.android.feature.chats.composer.MessageLinkAccessibility
import com.meshcoreone.android.feature.chats.composer.MessageLinkDispatcher
import com.meshcoreone.android.feature.chats.composer.MessageTextFormatter
import com.meshcoreone.android.feature.chats.composer.ui.ConversationLinkActions
import com.meshcoreone.android.feature.chats.composer.ui.MentionPickerSheet
import com.meshcoreone.android.feature.chats.composer.ui.MessageBody
import com.meshcoreone.android.feature.chats.composer.ui.rememberLinkActionNames
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun RoomConversationRoute(
    holder: RoomConversationStateHolder,
    links: ConversationLinkActions,
    modifier: Modifier = Modifier,
    failureText: @Composable (Throwable) -> String = { it.message ?: it.javaClass.simpleName },
) {
    val state by holder.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var authenticate by remember { mutableStateOf(false) }
    var pickerContext by remember { mutableStateOf<MentionPickerContext?>(null) }
    val dispatcher = remember(links) {
        MessageLinkDispatcher(
            links.contacts, links.radioId, links.connectedDeviceName,
            onMention = { outcome ->
                when (outcome) {
                    is MentionTapEvaluator.Outcome.Navigate -> links.navigateToContact(outcome.contact)
                    is MentionTapEvaluator.Outcome.Picker -> pickerContext = outcome.context
                }
            },
            route = links.route,
            openExternal = links.openExternal,
        )
    }
    LaunchedEffect(holder) {
        holder.start()
        holder.load()
    }
    RoomConversationContent(
        state = state,
        onDraftChanged = holder::updateDraft,
        onSend = { scope.launch { holder.send() } },
        onRetry = { scope.launch { holder.retry(it) } },
        onLink = dispatcher::dispatch,
        onReconnect = { authenticate = true; scope.launch { holder.prepareAuthentication() } },
        onDismissError = holder::clearFailure,
        failureText = failureText,
        modifier = modifier,
    )
    if (authenticate) {
        RoomAuthenticationSheet(
            state,
            onDismiss = { authenticate = false },
            onAuthenticate = { password, remember ->
                scope.launch {
                    holder.authenticate(password, remember)
                    if (holder.state.value.session.isConnected) authenticate = false
                }
            },
        )
    }
    pickerContext?.let { context ->
        MentionPickerSheet(
            context,
            onSelect = { contact -> pickerContext = null; links.navigateToContact(contact) },
            onDismiss = { pickerContext = null },
        )
    }
}

@Composable
fun RoomConversationContent(
    state: RoomConversationState,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (java.util.UUID) -> Unit,
    onLink: (String) -> Boolean,
    onReconnect: () -> Unit,
    onDismissError: () -> Unit,
    failureText: @Composable (Throwable) -> String,
    modifier: Modifier = Modifier,
) {
    val frame = LocalMeshTheme.current.frame
    Column(modifier.fillMaxSize().background(frame.canvas.toComposeColor()).imePadding()) {
        Text(
            state.session.name,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(16.dp).semantics { heading() },
        )
        state.failure?.let { failure ->
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onDismissError).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(failureText(failure), color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                Text("×")
            }
        }
        when {
            state.isLoading && !state.hasLoadedOnce -> {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(32.dp))
            }
            state.rows.isEmpty() -> {
                Text(
                    androidx.compose.ui.res.stringResource(R.string.l10n_app_remotenodes_remotenodes_room_nomessagesyet),
                    Modifier.align(Alignment.CenterHorizontally).padding(32.dp),
                )
            }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = false) {
                items(state.rows, key = { it.message.id }) { row ->
                    RoomMessageRowContent(row, onRetry, onLink)
                }
            }
        }
        when {
            !state.session.isConnected -> Button(
                onClick = onReconnect,
                enabled = !state.isAuthenticating,
                modifier = Modifier.fillMaxWidth().padding(16.dp).sharedTouchTarget(),
            ) {
                if (state.isAuthenticating) CircularProgressIndicator()
                else Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_room_taptoreconnect))
            }
            state.session.canPost -> Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    state.draft,
                    onDraftChanged,
                    Modifier.weight(1f).sharedTouchTarget(),
                    placeholder = { Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_input_accessibilitylabel)) },
                )
                Button(
                    onSend,
                    enabled = state.draft.isNotEmpty() && !state.isSending,
                    modifier = Modifier.sharedTouchTarget(),
                ) {
                    Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_input_sendmessage))
                }
            }
            else -> Text(
                androidx.compose.ui.res.stringResource(R.string.l10n_app_remotenodes_remotenodes_room_viewonlybanner),
                Modifier.fillMaxWidth().padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RoomMessageRowContent(row: RoomMessageRow, onRetry: (java.util.UUID) -> Unit, onLink: (String) -> Boolean) {
    val message = row.message
    val tokens = LocalMeshTheme.current
    val frame = tokens.frame
    val bubble = when {
        !message.isFromSelf -> frame.incomingBubble
        message.status == MessageStatus.FAILED ->
            AppColors.Message.outgoingBubbleFailed(frame.highContrast).compositeOver(frame.canvas)
        else -> frame.accent
    }
    val foreground = accessibleForeground(
        if (message.isFromSelf) frame.outgoingText else tokens.roles.onIncomingBubble,
        bubble,
        WCAGContrast.floor(frame.highContrast),
    )
    val statusLabel = stringResource(
        when (message.status) {
            MessageStatus.FAILED -> R.string.l10n_app_remotenodes_remotenodes_room_message_status_failedlabel
            MessageStatus.PENDING, MessageStatus.SENDING, MessageStatus.RETRYING ->
                R.string.l10n_app_remotenodes_remotenodes_room_message_status_sendinglabel
            MessageStatus.SENT, MessageStatus.DELIVERED ->
                R.string.l10n_app_remotenodes_remotenodes_room_message_status_deliveredlabel
        },
    )
    val label = if (message.isFromSelf) "${message.text}, $statusLabel" else "${message.authorDisplayName}: ${message.text}"
    val retryLabel = stringResource(R.string.l10n_app_chats_chats_message_action_retry)
    val names = rememberLinkActionNames()
    val formatted = remember(message.text, message.isFromSelf) {
        MessageTextFormatter.format(message.text, message.isFromSelf, currentUserName = null)
    }
    val linkActions = remember(formatted, names) { MessageLinkAccessibility.actions(null, formatted, names) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (row.showTimestamp) {
            Text(
                DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())
                    .withZone(ZoneId.systemDefault()).format(row.message.date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Replace child speech, not rendering/taps: one original-body label with the source's actions.
        Column(Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = label
            customActions = buildList {
                if (message.status == MessageStatus.FAILED) {
                    add(CustomAccessibilityAction(retryLabel) { onRetry(message.id); true })
                }
                addAll(linkActions.map { action -> CustomAccessibilityAction(action.name) { onLink(action.url) } })
            }
        }, horizontalAlignment = if (message.isFromSelf) Alignment.End else Alignment.Start) {
            if (row.showSenderName) Text(message.authorDisplayName, style = MaterialTheme.typography.labelMedium)
            Surface(
                modifier = Modifier.testTag("room-bubble:${message.id}"),
                color = bubble.toComposeColor(),
                contentColor = foreground.toComposeColor(),
                shape = MaterialTheme.shapes.large,
            ) {
                MessageBody(
                    message.text, message.isFromSelf, currentUserName = null, onLink = { onLink(it) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    baseColor = foreground.toComposeColor(),
                )
            }
            if (message.isFromSelf) {
                Text(
                    stringResource(
                        when (message.status) {
                            MessageStatus.PENDING, MessageStatus.SENDING -> R.string.l10n_app_chats_chats_message_status_sending
                            MessageStatus.SENT -> R.string.l10n_app_chats_chats_message_status_sent
                            MessageStatus.DELIVERED -> R.string.l10n_app_chats_chats_message_status_delivered
                            MessageStatus.FAILED -> R.string.l10n_app_chats_chats_message_status_failed
                            MessageStatus.RETRYING -> R.string.l10n_app_chats_chats_message_status_retrying
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (message.status == MessageStatus.FAILED) {
                TextButton({ onRetry(message.id) }, Modifier.sharedTouchTarget()) {
                    Text(stringResource(R.string.l10n_app_chats_chats_message_status_retry))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomAuthenticationSheet(
    state: RoomConversationState,
    onDismiss: () -> Unit,
    onAuthenticate: (String, Boolean) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(true) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.session.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            when {
                !state.authenticationLookupComplete -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                state.authenticationContact == null -> {
                    Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_roomauth_notfound_title))
                    Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_roomauth_notfound_description))
                }
                else -> {
                    OutlinedTextField(
                        password,
                        { password = it },
                        Modifier.fillMaxWidth().sharedTouchTarget(),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        label = { Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_remotenodes_remotenodes_auth_password)) },
                    )
                    Row(
                        Modifier.fillMaxWidth().sharedTouchTarget().toggleable(
                            value = rememberPassword,
                            role = Role.Switch,
                            onValueChange = { rememberPassword = it },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(rememberPassword, onCheckedChange = null)
                        Text(stringResource(R.string.l10n_app_remotenodes_remotenodes_auth_rememberpassword))
                    }
                    Button(
                        { onAuthenticate(password, rememberPassword) },
                        enabled = !state.isAuthenticating,
                        modifier = Modifier.fillMaxWidth().sharedTouchTarget(),
                    ) {
                        Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_remotenodes_remotenodes_auth_joinroom))
                    }
                }
            }
            TextButton(onDismiss, Modifier.align(Alignment.End).sharedTouchTarget()) {
                Text(androidx.compose.ui.res.stringResource(R.string.l10n_app_chats_chats_common_cancel))
            }
        }
    }
}
