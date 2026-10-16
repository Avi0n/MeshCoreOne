package one.meshcore.shared.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import one.meshcore.shared.chat.ChannelSenderTableStore
import one.meshcore.shared.chat.ChannelSenderTables
import one.meshcore.shared.chat.IncomingAvatarJPEGStore
import one.meshcore.shared.chat.pathdetail.loadContacts
import one.meshcore.shared.contact.collectContactUiReloads

/**
 * Loads the contact book that backs channel sender resolution (nickname match, key-prefix match, hex /
 * unknown fallback). Held in a state the resolver reads at each use, so contacts arriving after the first
 * page repaint the names instead of baking the unresolved wire name into the items.
 */
@Composable
internal fun rememberChannelSenderTables(environment: ChatThreadEnvironment): State<ChannelSenderTables> {
    val service = environment.contactService()
    val radio = environment.radioId()
    val tables = remember(radio) { mutableStateOf(ChannelSenderTableStore.tables(radio)) }
    val invalidations = environment.contactListInvalidations
    LaunchedEffect(service, radio, invalidations) {
        suspend fun reload() {
            val next = ChannelSenderTables.from(loadContacts(service, radio))
            IncomingAvatarJPEGStore.replace(next.contacts, next.incomingAvatars.values)
            if (radio != null) ChannelSenderTableStore.replace(radio, next)
            tables.value = next
        }
        reload()
        // iOS ChatConversationView loadAllContacts on contactsVersion (channel incoming avatars).
        collectContactUiReloads(service, invalidations) { reload() }
    }
    return tables
}
