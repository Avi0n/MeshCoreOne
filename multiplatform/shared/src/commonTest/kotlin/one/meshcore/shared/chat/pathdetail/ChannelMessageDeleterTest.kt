package one.meshcore.shared.chat.pathdetail

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.backup.ReactionDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ports the Swift `BlockedSenderMessageDeletionTests`: the cascade deletes a named sender's channel
 * messages (and their reactions, repeats, and pending sends), preserves that sender's DMs, is scoped to the
 * radio, no-ops for an unknown sender, and preserves null-sender rows.
 */
class ChannelMessageDeleterTest {
    private val radioID = ChatFixtures.RADIO
    private val otherRadio: Uuid = ChatFixtures.freshUuid()

    private fun channelMessage(
        senderName: String?,
        radioID: Uuid = this.radioID,
        channelIndex: Int = 0,
        id: Uuid = ChatFixtures.freshUuid(),
    ): MessageDto = ChatFixtures.message(
        id = id,
        direction = MessageDirection.INCOMING,
        contactID = null,
        channelIndex = channelIndex,
        senderNodeName = senderName,
    ).copy(radioID = radioID)

    private fun dmMessage(senderName: String, id: Uuid = ChatFixtures.freshUuid()): MessageDto = ChatFixtures.message(
        id = id,
        direction = MessageDirection.INCOMING,
        contactID = ChatFixtures.CONTACT,
        channelIndex = null,
        senderNodeName = senderName,
    )

    private suspend fun seededStore(
        seed: suspend InMemoryChannelMessageDeletionStore.() -> Unit,
    ): InMemoryChannelMessageDeletionStore {
        val store = InMemoryChannelMessageDeletionStore()
        store.seed()
        return store
    }

    @Test
    fun deletesAllChannelMessagesFromANamedSender() = runTest {
        val spam1 = channelMessage("Spammer", channelIndex = 0)
        val spam2 = channelMessage("Spammer", channelIndex = 1)
        val legit = channelMessage("Legit", channelIndex = 0)
        val store = seededStore {
            seedMessage(spam1)
            seedMessage(spam2)
            seedMessage(legit)
        }

        ChannelMessageDeleter(store).deleteChannelMessages("Spammer", radioID)

        assertNull(store.message(spam1.id))
        assertNull(store.message(spam2.id))
        assertNotNull(store.message(legit.id))
    }

    @Test
    fun doesNotDeleteDMsFromTheSameSender() = runTest {
        val channel = channelMessage("Spammer")
        val dm = dmMessage("Spammer")
        val store = seededStore {
            seedMessage(channel)
            seedMessage(dm)
        }

        ChannelMessageDeleter(store).deleteChannelMessages("Spammer", radioID)

        assertNull(store.message(channel.id))
        assertNotNull(store.message(dm.id))
    }

    @Test
    fun scopesDeletionToTheRadio() = runTest {
        val here = channelMessage("Spammer", radioID = radioID)
        val elsewhere = channelMessage("Spammer", radioID = otherRadio)
        val store = seededStore {
            seedMessage(here)
            seedMessage(elsewhere)
        }

        ChannelMessageDeleter(store).deleteChannelMessages("Spammer", radioID)

        assertNull(store.message(here.id))
        assertNotNull(store.message(elsewhere.id))
    }

    @Test
    fun noOpWhenSenderHasNoMessages() = runTest {
        val legit = channelMessage("Legit")
        val store = seededStore { seedMessage(legit) }

        ChannelMessageDeleter(store).deleteChannelMessages("Ghost", radioID)

        assertNotNull(store.message(legit.id))
    }

    @Test
    fun deletesReactionsRepeatsAndPendingSendsForDeletedMessages() = runTest {
        val spam = channelMessage("Spammer")
        val legit = channelMessage("Legit")
        val spamReaction = reaction(spam.id)
        val legitReaction = reaction(legit.id)
        val store = seededStore {
            seedMessage(spam)
            seedMessage(legit)
            seedReaction(spamReaction)
            seedReaction(legitReaction)
            seedRepeat(repeat(spam.id))
            seedPendingSend(spam.id)
        }

        ChannelMessageDeleter(store).deleteChannelMessages("Spammer", radioID)

        assertTrue(store.reactions(spam.id).isEmpty())
        assertEquals(1, store.reactions(legit.id).size)
        assertTrue(store.repeats(spam.id).isEmpty())
        assertFalse(store.hasPendingSend(spam.id))
    }

    @Test
    fun preservesNullSenderRows() = runTest {
        val systemRow = channelMessage(senderName = null)
        val store = seededStore { seedMessage(systemRow) }

        ChannelMessageDeleter(store).deleteChannelMessages("AnyName", radioID)

        assertNotNull(store.message(systemRow.id))
    }

    private fun reaction(messageID: Uuid): ReactionDto = ReactionDto(
        id = ChatFixtures.freshUuid(),
        messageID = messageID,
        emoji = "👍",
        senderName = "Reactor",
        messageHash = "AABB1122",
        rawText = ":thumbsup:",
        receivedAt = 1_700_000_000.0,
        radioID = radioID,
        channelIndex = 0,
    )

    private fun repeat(messageID: Uuid): MessageRepeatDto = MessageRepeatDto(
        id = ChatFixtures.freshUuid(),
        messageID = messageID,
        receivedAt = 1_700_000_000.0,
        pathNodes = byteArrayOf(0x42),
        pathLength = 0x01,
    )
}
