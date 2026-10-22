// PortedFrom: MC1/Views/Chats/Components/MentionSuggestionView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/MentionSuggestionRow.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Mentions/ChatConversationMentionOverlay.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Mentions/MentionPickerSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.composer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.ui.ContactAvatar
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.chats.composer.MentionPickerContext
import com.meshcoreone.android.feature.chats.list.ui.ContactMatchRow
import com.meshcoreone.android.feature.chats.list.ui.ContactMatchSelection
import java.time.Clock

private val MAX_LIST_HEIGHT = 200.dp
private val ROW_AVATAR = 32.dp

/** The `@` overlay: at most 20 chat contacts, 200dp tall, anchored above the bar by the caller. */
@Composable
fun MentionSuggestionList(contacts: List<ContactDTO>, onSelect: (ContactDTO) -> Unit, modifier: Modifier = Modifier) {
    if (contacts.isEmpty()) return
    val listLabel = stringResource(AppChatsStrings.chatsSuggestionsAccessibilityLabel)
    Surface(
        modifier.semantics { contentDescription = listLabel },
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
    ) {
        LazyColumn(Modifier.heightIn(max = MAX_LIST_HEIGHT)) {
            items(contacts, key = { it.id }) { contact ->
                MentionSuggestionRow(contact, onSelect)
                if (contact != contacts.last()) HorizontalDivider(Modifier.padding(start = 52.dp))
            }
        }
    }
}

@Composable
private fun MentionSuggestionRow(contact: ContactDTO, onSelect: (ContactDTO) -> Unit) {
    val resources = LocalResources.current
    val label = AppChatsStrings.chatsMentionAccessibilityLabel(resources, contact.displayName)
    val hint = stringResource(AppChatsStrings.chatsMentionAccessibilityHintContact)
    Row(
        Modifier
            .fillMaxWidth()
            .sharedTouchTarget()
            .clickable(onClickLabel = hint, role = Role.Button) { onSelect(contact) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(contact.displayName, ROW_AVATAR)
        Text(contact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Disambiguation / status sheet for a tapped `@mention` that is not exactly one saved contact:
 * the user's own name, an unknown name, or several matches to choose from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MentionPickerSheet(
    context: MentionPickerContext,
    onSelect: (ContactDTO) -> Unit,
    onDismiss: () -> Unit,
    clock: Clock = Clock.systemDefaultZone(),
) {
    val resources = LocalResources.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LazyColumn(
                Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        AppChatsStrings.chatsMentionPickerTitle(resources, context.name),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                when {
                    context.isSelfMention -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(AppChatsStrings.chatsMentionPickerSelfTitle), style = MaterialTheme.typography.titleSmall)
                            Text(AppChatsStrings.chatsMentionPickerSelfSubtitle(resources, context.name))
                        }
                    }
                    context.matches.isEmpty() -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(AppChatsStrings.chatsMentionPickerNotSavedTitle), style = MaterialTheme.typography.titleSmall)
                            Text(AppChatsStrings.chatsMentionPickerNotSavedSubtitle(resources, context.name))
                        }
                    }
                    else -> {
                        item {
                            Text(stringResource(AppChatsStrings.chatsMentionPickerMatchingContacts), style = MaterialTheme.typography.titleSmall)
                        }
                        items(context.matches, key = { it.id }) { match ->
                            ContactMatchRow(match, ContactMatchSelection.Tap, clock, onClick = { onSelect(match) })
                        }
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.sharedTouchTarget()) {
                Text(stringResource(AppChatsStrings.chatsCommonDone))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
