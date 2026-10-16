package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.ReactionDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.Uuid
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Room [RoomReactionStore] against a file-backed database: reaction save/fetch plus the contact,
 * channel, and message reads the notification path uses.
 */
class RoomReactionStoreTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var store: RoomReactionStore

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("room-reaction-store", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        store = RoomReactionStore(
            reactionDao = db.reactionDao(),
            messageDao = db.messageDao(),
            contactDao = db.contactDao(),
            channelDao = db.channelDao(),
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun savedReactionAndTargetRowsRoundTrip() = runBlocking {
        val contact = ChatFixtures.contact(name = "Ada", id = ChatFixtures.CONTACT)
        val channel = ChatFixtures.channel(name = "Mesh", index = CHANNEL_INDEX)
        val message = ChatFixtures.message(text = "hello mesh")
        db.contactDao().upsert(contact.toEntity())
        db.channelDao().upsert(channel.toEntity())
        db.messageDao().upsert(message.toEntity())
        val reaction = reaction(messageID = message.id)

        store.saveReaction(reaction)

        assertEquals(reaction.emoji, store.fetchReactions(message.id).single().emoji)
        assertTrue(store.reactionExists(message.id, reaction.senderName, reaction.emoji))
        assertEquals(contact.name, store.fetchContact(contact.id)?.name)
        assertEquals(channel.name, store.fetchChannel(ChatFixtures.RADIO, CHANNEL_INDEX)?.name)
        assertEquals(message.text, store.fetchMessage(message.id)?.text)

        store.updateMessageReactionSummary(message.id, SUMMARY)
        assertEquals(SUMMARY, store.fetchMessage(message.id)?.reactionSummary)
    }

    private fun reaction(messageID: Uuid) = ReactionDto(
        id = ChatFixtures.freshUuid(),
        messageID = messageID,
        emoji = EMOJI,
        senderName = "Ada",
        messageHash = "ABCDEFGH",
        rawText = "$EMOJI\nABCDEFGH",
        receivedAt = RECEIVED_AT,
        radioID = ChatFixtures.RADIO,
        channelIndex = CHANNEL_INDEX,
        contactID = ChatFixtures.CONTACT,
    )

    private companion object {
        const val CHANNEL_INDEX = 3
        const val EMOJI = "👍"
        const val SUMMARY = "$EMOJI 1"
        const val RECEIVED_AT = 1_700_000_000.0
    }
}
