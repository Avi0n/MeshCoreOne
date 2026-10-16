package one.meshcore.shared.chat

import one.meshcore.shared.channel.ChannelSecret
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The composer's lock glyph must never claim encryption a broadcast does not have. Mirrors the Swift
 * `ChatConversationInputBar`, which derives `isEncrypted` from the conversation type: always true for a
 * DM, `Channel.isEncryptedChannel` for a channel.
 */
class ComposerEncryptionLockTest {
    private val radioID = ChatFixtures.RADIO

    private fun channelConversation(name: String, index: Int) =
        ChatConversationID.channel(radioID, index) to ChatFixtures.channel(name = name, index = index)

    @Test
    fun directMessageIsAlwaysEncrypted() {
        val id = ChatConversationID.dm(radioID, ChatFixtures.contact(name = "Alice").id)
        assertTrue(composerShowsEncryptedLock(id, channel = null))
    }

    @Test
    fun publicChannelIsNotEncrypted() {
        val (id, channel) = channelConversation(name = "Public", index = ChannelSecret.PUBLIC_CHANNEL_SLOT)
        assertFalse(channel.isEncryptedChannel)
        assertFalse(composerShowsEncryptedLock(id, channel))
    }

    @Test
    fun hashtagChannelIsNotEncrypted() {
        // A hashtag channel's key is derived from its public name, so anyone running the client can read it.
        val (id, channel) = channelConversation(name = "#avion-testing", index = 2)
        assertFalse(channel.isEncryptedChannel)
        assertFalse(composerShowsEncryptedLock(id, channel))
    }

    @Test
    fun privateChannelIsEncrypted() {
        val (id, channel) = channelConversation(name = "Ops", index = 1)
        assertTrue(channel.isEncryptedChannel)
        assertTrue(composerShowsEncryptedLock(id, channel))
    }

    @Test
    fun channelThreadWithNoResolvedRowReadsAsUnencrypted() {
        // The closed lock is never shown before the channel row resolves; the open lock is the safe default.
        assertFalse(composerShowsEncryptedLock(ChatConversationID.channel(radioID, 4), channel = null))
    }
}
