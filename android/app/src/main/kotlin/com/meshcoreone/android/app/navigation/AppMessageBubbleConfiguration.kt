// PortedFrom: MC1/Views/Chats/Components/MessageBubbleConfiguration.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+Messages.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatMessageBakeState+ItemBuild.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.rendering.IncomingAvatarIdentity
import com.meshcoreone.android.core.ui.AvatarImageCache
import com.meshcoreone.android.core.ui.AvatarImageState
import com.meshcoreone.android.core.ui.ContactAvatar
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MessageBubbleConfiguration(
    val showSenderName: Boolean,
    val showsIncomingAvatars: Boolean,
) {
    companion object {
        val directMessage = MessageBubbleConfiguration(false, false)
        val channel = MessageBubbleConfiguration(true, true)

        fun forConversation(conversation: TimelineConversation): MessageBubbleConfiguration =
            when (conversation) {
                is TimelineConversation.Channel -> channel
                is TimelineConversation.Direct -> directMessage
            }

        fun buildNicknameLookup(contacts: List<ContactDTO>): Map<String, String> =
            uniqueNamedContacts(contacts).mapNotNull { (name, contact) ->
                contact.nickname?.takeIf(String::isNotEmpty)?.let { name to it }
            }.toMap()

        fun incomingAvatarIdentities(contacts: List<ContactDTO>): Map<String, IncomingAvatarIdentity> =
            uniqueNamedContacts(contacts).mapValues { (_, contact) ->
                IncomingAvatarIdentity(
                    contact.name, contact.id, IncomingAvatarIdentity.revision(contact.avatarImageData),
                )
            }

        private fun uniqueNamedContacts(contacts: List<ContactDTO>): Map<String, ContactDTO> =
            contacts.groupBy { loweredName(it.name) }.mapNotNull { (name, matches) ->
                matches.singleOrNull()?.let { name to it }
            }.toMap()

        // Swift's scalar lowercase and canonically equivalent dictionary keys, not JVM final sigma.
        fun loweredName(name: String): String {
            val lowered = buildString {
                name.codePoints().forEach { scalar ->
                    if (scalar == 0x130) append("i\u0307") else appendCodePoint(Character.toLowerCase(scalar))
                }
            }
            return Normalizer.normalize(lowered, Normalizer.Form.NFC)
        }
    }
}

internal data class ChannelAvatarTable(
    val identities: Map<String, IncomingAvatarIdentity>,
    val images: Map<UUID, Bytes>,
) {
    companion object {
        val EMPTY = ChannelAvatarTable(emptyMap(), emptyMap())

        fun fromContacts(contacts: List<ContactDTO>): ChannelAvatarTable {
            val identities = MessageBubbleConfiguration.incomingAvatarIdentities(contacts)
            val matchedIds = identities.values.mapNotNull { it.matchedContactID }.toHashSet()
            val images = contacts.mapNotNull { contact ->
                contact.avatarImageData?.takeIf { it.size > 0 && contact.id in matchedIds }?.let { contact.id to it }
            }.toMap()
            return ChannelAvatarTable(identities, images)
        }
    }
}

@Composable
internal fun rememberChannelAvatars(contacts: List<ContactDTO>): ChannelAvatarTable {
    val table by produceState(ChannelAvatarTable.EMPTY, contacts) {
        value = withContext(Dispatchers.Default) { ChannelAvatarTable.fromContacts(contacts) }
    }
    return table
}

@Composable
internal fun AppChannelAvatar(
    message: MessageDTO,
    table: ChannelAvatarTable,
    contacts: List<ContactDTO>,
    cache: AvatarImageCache,
) {
    val resources = LocalResources.current
    val displayName = bubbleSenderResolution(message, contacts, emptyMap(), resources).displayName
    val identity = IncomingAvatarIdentity.resolve(message.senderNodeName, displayName, table.identities)
    val data = identity.matchedContactID?.let(table.images::get)
    key(identity, data) {
        val image by produceState<AvatarImageState>(AvatarImageState.Absent, cache, data) {
            value = withContext(Dispatchers.IO) { cache.decode(data) }
        }
        ContactAvatar(name = identity.name, size = 28.dp, image = image)
    }
}
