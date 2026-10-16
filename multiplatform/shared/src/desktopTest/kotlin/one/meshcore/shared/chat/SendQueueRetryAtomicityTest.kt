package one.meshcore.shared.chat

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ChatDaos
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.RoomChatStore
import one.meshcore.shared.persistence.RoomPendingSendStore
import one.meshcore.shared.persistence.RxLogCorrelator
import one.meshcore.shared.persistence.meshDatabase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Manual retry replaces the parked queue row and flips the message back to `.pending`. Those writes must
 * land together: a half-applied replace leaves the message `.pending` with no queue row, so it never
 * sends, shows the in-progress glyph, and offers no retry (retry renders only on `.failed`).
 */
class SendQueueRetryAtomicityTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var store: DefaultSendQueueStore

    private val radioID = ChatFixtures.RADIO
    private val contactID = ChatFixtures.CONTACT
    private lateinit var chatStore: RoomChatStore

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("send-queue-retry", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        chatStore = RoomChatStore(
            radioID,
            ChatDaos(
                db.messageDao(),
                db.contactDao(),
                db.channelDao(),
                db.messageRepeatDao(),
                db.pendingSendDao(),
                db.reactionDao(),
            ),
            RxLogCorrelator(db.rxLogEntryDao()),
        )
        store = DefaultSendQueueStore(RoomPendingSendStore(db.pendingSendDao()), chatStore) { null }
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun `retry replaces the queue row and revives the message`() = runBlocking {
        val message = failedMessage()
        chatStore.saveMessage(message)
        store.upsertPendingSend(row(id = PARKED_ROW, messageID = message.id, sequence = 1))
        // Another radio row holds the high-water mark, so the replacement must take the next sequence.
        store.upsertPendingSend(row(id = TAKEN_ROW, messageID = freshId(), sequence = HIGH_WATER))

        val sequence = store.replacePendingSendForRetry(message.id, row(id = freshId(), messageID = message.id))

        assertEquals(HIGH_WATER + 1, sequence)
        val rows = store.fetchPendingSendsForMessage(message.id)
        assertEquals(1, rows.size)
        assertEquals(sequence, rows.single().sequence)
        assertEquals(MessageStatus.PENDING, chatStore.fetchMessage(message.id)?.status)
    }

    @Test
    fun `retry never downgrades a message a late ack already delivered`() = runBlocking {
        val message = failedMessage().copy(status = MessageStatus.DELIVERED)
        chatStore.saveMessage(message)
        store.upsertPendingSend(row(id = PARKED_ROW, messageID = message.id, sequence = 1))

        store.replacePendingSendForRetry(message.id, row(id = freshId(), messageID = message.id))

        assertEquals(MessageStatus.DELIVERED, chatStore.fetchMessage(message.id)?.status)
    }

    @Test
    fun `a failed insert rolls the whole replace back`() = runBlocking {
        // A write fault partway through stands in for process death inside the same window: whatever kills
        // the insert must also undo the delete and the status flip.
        val message = failedMessage()
        chatStore.saveMessage(message)
        store.upsertPendingSend(row(id = PARKED_ROW, messageID = message.id, sequence = 1))
        val otherMessage = freshId()
        store.upsertPendingSend(row(id = TAKEN_ROW, messageID = otherMessage, sequence = 2))

        assertFails {
            // Re-using a live row id makes the insert violate the primary key.
            store.replacePendingSendForRetry(message.id, row(id = TAKEN_ROW, messageID = message.id))
        }

        val rows = store.fetchPendingSendsForMessage(message.id)
        assertTrue(rows.any { it.id == PARKED_ROW }, "the parked queue row must survive a failed replace")
        assertEquals(MessageStatus.FAILED, chatStore.fetchMessage(message.id)?.status)
    }

    private fun failedMessage() = ChatFixtures.message(
        text = "needs a retry",
        status = MessageStatus.FAILED,
        contactID = contactID,
        channelIndex = null,
    )

    private fun row(id: Uuid, messageID: Uuid, sequence: Int = 0) = PendingSendDto(
        id = id,
        radioID = radioID,
        messageID = messageID,
        kind = PendingSendKind.DM,
        sequence = sequence,
        enqueuedAtEpochSeconds = ChatFixtures.BASE_EPOCH_SECONDS,
        contactID = contactID,
        attemptCount = 0,
    )

    private fun freshId(): Uuid = ChatFixtures.freshUuid()

    private companion object {
        const val HIGH_WATER = 5
        val PARKED_ROW: Uuid = Uuid.normalized("11111111-1111-1111-1111-111111111111")
        val TAKEN_ROW: Uuid = Uuid.normalized("22222222-2222-2222-2222-222222222222")
    }
}
