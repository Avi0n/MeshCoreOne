package one.meshcore.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.pathdetail.loadContacts
import one.meshcore.shared.chat.text.MentionTapEvaluator
import one.meshcore.shared.chat.text.MentionTapResult
import one.meshcore.shared.chat.ui.mentionPickerEmptyIcon
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_mention_picker_matching_contacts
import one.meshcore.shared.resources.chats_mention_picker_not_saved_subtitle
import one.meshcore.shared.resources.chats_mention_picker_not_saved_title
import one.meshcore.shared.resources.chats_mention_picker_self_subtitle
import one.meshcore.shared.resources.chats_mention_picker_self_title
import one.meshcore.shared.resources.chats_mention_picker_title
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MentionPickerSheetHost(
    sheet: ShellSheet.MentionPicker,
    deps: ShellDependencies,
    onDismiss: () -> Unit,
    onOpenContact: (String) -> Unit,
) {
    MentionPickerSheet(
        sheet = sheet,
        contactService = deps.contactService(),
        radioId = deps.currentRadioId(),
        localNodeName = deps.localNodeName.value,
        onDismiss = onDismiss,
        onOpenContact = onOpenContact,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MentionPickerSheet(
    sheet: ShellSheet.MentionPicker,
    contactService: ContactService?,
    radioId: Uuid?,
    localNodeName: String?,
    onDismiss: () -> Unit,
    onOpenContact: (String) -> Unit,
) {
    var matches by remember(sheet) { mutableStateOf(sheet.matches) }
    var isSelf by remember(sheet) { mutableStateOf(sheet.isSelfMention) }
    var loaded by remember(sheet) { mutableStateOf(false) }
    LaunchedEffect(sheet, contactService, radioId, localNodeName) {
        if (loaded) {
            loaded = false
            matches = emptyList()
            isSelf = false
        }
        val contacts = loadContacts(contactService, radioId)
        when (
            val result = MentionTapEvaluator.evaluate(
                name = sheet.name,
                contacts = contacts,
                connectedDeviceName = localNodeName,
            )
        ) {
            is MentionTapResult.NavigateToContact -> {
                onOpenContact(result.contact.id.value)
            }
            is MentionTapResult.Picker -> {
                matches = result.matches
                isSelf = result.isSelfMention
                loaded = true
            }
        }
    }
    if (!loaded && matches.isEmpty() && !isSelf) {
        LoadingCenter(modifier = Modifier.fillMaxSize())
        return
    }
    Scaffold(
        topBar = {
            AppNestedTopBar(
                title = formatTemplate(
                    stringResource(Res.string.chats_mention_picker_title),
                    listOf(sheet.name),
                ),
                onBack = onDismiss,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            when {
                isSelf -> MentionPickerEmptyPane(
                    isSelfMention = true,
                    title = stringResource(Res.string.chats_mention_picker_self_title),
                    subtitle = formatTemplate(
                        stringResource(Res.string.chats_mention_picker_self_subtitle),
                        listOf(sheet.name),
                    ),
                )
                matches.isEmpty() -> MentionPickerEmptyPane(
                    isSelfMention = false,
                    title = stringResource(Res.string.chats_mention_picker_not_saved_title),
                    subtitle = formatTemplate(
                        stringResource(Res.string.chats_mention_picker_not_saved_subtitle),
                        listOf(sheet.name),
                    ),
                )
                else -> {
                    PreferenceHubSection(
                        title = stringResource(Res.string.chats_mention_picker_matching_contacts),
                        applyHorizontalPadding = false,
                    ) {
                        matches.forEach { contact ->
                            ListItem(
                                headlineContent = { Text(contact.displayName) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable { onOpenContact(contact.id.value) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MentionPickerEmptyPane(isSelfMention: Boolean, title: String, subtitle: String) {
    EmptyMessageColumn(
        title = title,
        icon = mentionPickerEmptyIcon(isSelfMention),
        description = subtitle,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    )
}
