package one.meshcore.shared.chat

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.channel.ChannelSecret

/** Name prefix marking a hashtag channel, whose key is derived from the publicly known name. */
internal const val HASHTAG_CHANNEL_PREFIX = "#"

/**
 * Whether a channel uses meaningful encryption. The public channel (slot 0) and hashtag channels derive
 * their secret from a publicly known string, so anyone can read them; only a named private channel is
 * end-to-end encrypted. Mirrors the Swift `Channel.isEncryptedChannel`.
 */
val ChannelDto.isEncryptedChannel: Boolean
    get() = index != ChannelSecret.PUBLIC_CHANNEL_SLOT && !name.startsWith(HASHTAG_CHANNEL_PREFIX)

/**
 * The composer's lock glyph state for a conversation: a direct message is always end-to-end encrypted, a
 * channel only when its key is private. A channel thread whose row is not resolved yet reads as
 * unencrypted, so the closed lock is never shown over a broadcast that may be readable by anyone.
 */
fun composerShowsEncryptedLock(conversationID: ChatConversationID, channel: ChannelDto?): Boolean =
    when (conversationID.conversation) {
        is ChatConversationID.ConversationKey.Dm -> true
        is ChatConversationID.ConversationKey.Channel -> channel?.isEncryptedChannel == true
    }
