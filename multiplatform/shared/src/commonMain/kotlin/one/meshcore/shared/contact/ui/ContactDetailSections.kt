package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ui.conversationTimestampLabel
import one.meshcore.shared.contact.ContactDetailLogic
import one.meshcore.shared.contact.ContactShareContent
import one.meshcore.shared.contact.heardOnAirSeconds
import one.meshcore.shared.contact.publicKeyHex
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_cancel
import one.meshcore.shared.resources.contacts_common_edit
import one.meshcore.shared.resources.contacts_common_save
import one.meshcore.shared.resources.contacts_detail_clear_messages
import one.meshcore.shared.resources.contacts_detail_danger_zone
import one.meshcore.shared.resources.contacts_detail_delete_type
import one.meshcore.shared.resources.contacts_detail_info
import one.meshcore.shared.resources.contacts_detail_last_advert
import one.meshcore.shared.resources.contacts_detail_last_heard
import one.meshcore.shared.resources.contacts_detail_name
import one.meshcore.shared.resources.contacts_detail_nickname
import one.meshcore.shared.resources.contacts_detail_nickname_none
import one.meshcore.shared.resources.contacts_detail_public_key
import one.meshcore.shared.resources.contacts_detail_technical
import one.meshcore.shared.resources.contacts_detail_type
import one.meshcore.shared.resources.contacts_detail_unread_messages
import one.meshcore.shared.resources.contacts_qr_copied
import one.meshcore.shared.resources.contacts_qr_copy
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.plainTextClipEntry
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ContactDetailInfoSection(
    contact: ContactDto,
    isEditingNickname: Boolean,
    nicknameDraft: String,
    onNicknameDraft: (String) -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSaveNickname: () -> Unit,
    nowSeconds: Long,
) {
    DetailSection(title = stringResource(Res.string.contacts_detail_info)) {
        if (isEditingNickname) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                AppOutlinedTextField(
                    value = nicknameDraft,
                    onValueChange = onNicknameDraft,
                    label = { Text(stringResource(Res.string.contacts_detail_nickname)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
                FilledTonalButton(
                    onClick = onSaveNickname,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(stringResource(Res.string.contacts_common_save))
                }
                TextButton(
                    onClick = onCancelEdit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.contacts_common_cancel))
                }
            }
        } else {
            val nicknameValue = contact.nickname?.takeIf { it.isNotEmpty() }
                ?: stringResource(Res.string.contacts_detail_nickname_none)
            val editLabel = stringResource(Res.string.contacts_common_edit)
            DetailInfoItem(
                headline = stringResource(Res.string.contacts_detail_nickname),
                supporting = nicknameValue,
                icon = AppIcons.Tag,
                trailing = {
                    AppIconButton(
                        imageVector = AppIcons.Edit,
                        contentDescription = editLabel,
                        onClick = onStartEdit,
                    )
                },
            )
        }
        DetailInfoItem(
            headline = stringResource(Res.string.contacts_detail_name),
            supporting = contact.name,
            icon = AppIcons.Person,
        )
        contact.heardOnAirSeconds?.let { heard ->
            DetailInfoItem(
                headline = stringResource(Res.string.contacts_detail_last_heard),
                supporting = conversationTimestampLabel(heard, nowSeconds),
                icon = AppIcons.Timeline,
            )
        }
        if (contact.lastAdvertTimestamp > 0) {
            DetailInfoItem(
                headline = stringResource(Res.string.contacts_detail_last_advert),
                supporting = conversationTimestampLabel(contact.lastAdvertTimestamp, nowSeconds),
                icon = AppIcons.Sensors,
            )
        }
        if (contact.unreadCount > 0) {
            DetailInfoItem(
                headline = stringResource(Res.string.contacts_detail_unread_messages),
                supporting = contact.unreadCount.toString(),
                icon = AppIcons.Chat,
                supportingColor = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun ContactDetailTechnicalSection(contact: ContactDto, typeLabel: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    var showCopyKeyFeedback by remember { mutableStateOf(false) }
    LaunchedEffect(showCopyKeyFeedback) {
        if (showCopyKeyFeedback) {
            delay(ContactShareContent.COPY_FEEDBACK_MS)
            showCopyKeyFeedback = false
        }
    }
    DetailSection(title = stringResource(Res.string.contacts_detail_technical)) {
        DetailInfoItem(
            headline = stringResource(Res.string.contacts_detail_public_key),
            supporting = contact.publicKeyHex,
            icon = AppIcons.Lock,
            monospace = true,
        )
        DetailActionItem(
            headline = stringResource(
                if (showCopyKeyFeedback) Res.string.contacts_qr_copied else Res.string.contacts_qr_copy,
            ),
            icon = AppIcons.ContentCopy,
            enabled = !showCopyKeyFeedback,
            onClick = {
                scope.launch {
                    clipboard.setClipEntry(
                        plainTextClipEntry(ContactShareContent.compactPublicKeyHex(contact.publicKey)),
                    )
                    haptics.success()
                    showCopyKeyFeedback = true
                }
            },
        )
        DetailInfoItem(
            headline = stringResource(Res.string.contacts_detail_type),
            supporting = typeLabel,
            icon = AppIcons.Info,
        )
    }
}

@Composable
internal fun ContactDetailDangerSection(
    actionFlags: ContactDetailLogic.ActionFlags,
    allowDelete: Boolean,
    typeLabel: String,
    enablement: ContactDetailLogic.ActionEnablement,
    radioHint: String?,
    onClearMessages: () -> Unit,
    onDelete: () -> Unit,
) {
    val deleteLabel = formattedStringResource(Res.string.contacts_detail_delete_type, typeLabel)
    DetailSection(
        title = stringResource(Res.string.contacts_detail_danger_zone),
        danger = true,
    ) {
        if (actionFlags.clearMessages) {
            DetailActionItem(
                headline = stringResource(Res.string.contacts_detail_clear_messages),
                icon = AppIcons.Delete,
                onClick = onClearMessages,
                destructive = true,
                tonalSurface = false,
            )
        }
        if (allowDelete) {
            DetailActionItem(
                headline = deleteLabel,
                icon = AppIcons.Delete,
                enabled = enablement.delete,
                onClick = onDelete,
                destructive = true,
                radioHint = radioHint,
                tonalSurface = false,
            )
        }
    }
}
