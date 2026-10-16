package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ChatDaos
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.RoomChatStore
import one.meshcore.shared.persistence.RxLogCorrelator
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.persistence.toDto
import one.meshcore.shared.persistence.toEntity
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * A reconnect must never rewrite a historical outgoing DM. iOS starts each connection with an empty
 * pending-ACK map and only logs an ACK it cannot match, so a `.sent` DM whose tracking died with the
 * previous connection stays `.sent`. These tests drive the real Room store through the connection
 * lifecycle `ServiceContainer.start` uses — [MessageService.startEventMonitoring] followed by the expiry
 * checker — and assert the rows survive, including rows belonging to another radio.
 */
class MessageServiceReconnectSweepTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var session: FakeChatSession

    private val connectedRadio = ChatFixtures.RADIO
    private val otherRadio: Uuid = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("message-service-reconnect", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        session = FakeChatSession()
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    @Test
    fun reconnectLeavesAHistoricalSentDirectMessageSent() = runBlocking {
        val id = seedSentDirectMessage(radioID = connectedRadio, ackCode = 0x11223344)

        reconnect()

        assertEquals(MessageStatus.SENT, statusOf(id), "historical .sent DM must survive a reconnect")
    }

    @Test
    fun reconnectLeavesAnotherRadiosSentDirectMessageAlone() = runBlocking {
        val mine = seedSentDirectMessage(radioID = connectedRadio, ackCode = 0x11223344)
        val theirs = seedSentDirectMessage(radioID = otherRadio, ackCode = 0x55667788)

        reconnect()

        assertEquals(MessageStatus.SENT, statusOf(theirs), "another radio's DM is outside this connection")
        assertEquals(MessageStatus.SENT, statusOf(mine), "connected radio's DM must survive")
    }

    /**
     * Runs the ACK lifecycle a connection starts, then one expiry pass. The listener subscribes to the
     * session stream as its last step, so an observed subscription orders the checker after everything
     * the listener start does.
     */
    private suspend fun reconnect() {
        val service = MessageService(
            session = session,
            store = RoomChatStore(
                connectedRadio,
                ChatDaos(
                    db.messageDao(),
                    db.contactDao(),
                    db.channelDao(),
                    db.messageRepeatDao(),
                    db.pendingSendDao(),
                    db.reactionDao(),
                ),
                RxLogCorrelator(db.rxLogEntryDao()),
            ),
            scope = scope,
            identity = MessageServiceIdentity(now = { NOW_SECONDS }, newMessageId = ChatFixtures::freshUuid),
        )
        service.startEventMonitoring()
        withTimeout(LISTENER_TIMEOUT) { session.eventSubscriptionCount.first { it > 0 } }
        service.checkExpiredAcks()
    }

    /** An outgoing DM the radio accepted days ago, still `.sent` and still carrying its expected ACK code. */
    private suspend fun seedSentDirectMessage(radioID: Uuid, ackCode: Long): Uuid {
        val message = ChatFixtures.message(status = MessageStatus.SENT, createdAt = SENT_DAYS_AGO_SECONDS)
            .copy(radioID = radioID, ackCode = ackCode)
        db.messageDao().upsert(message.toEntity())
        return message.id
    }

    private suspend fun statusOf(id: Uuid): MessageStatus? = db.messageDao().getById(id)?.toDto()?.status

    private companion object {
        const val NOW_SECONDS: Double = 1_700_000_000.0
        const val SENT_DAYS_AGO_SECONDS: Double = NOW_SECONDS - 3 * 24 * 60 * 60
        val LISTENER_TIMEOUT = 5.seconds
    }
}
