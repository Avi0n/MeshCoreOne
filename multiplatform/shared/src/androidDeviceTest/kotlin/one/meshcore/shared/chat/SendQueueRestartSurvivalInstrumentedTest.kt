package one.meshcore.shared.chat

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.RoomPendingSendStore
import one.meshcore.shared.persistence.meshDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

/**
 * THE W2-S3 restart-survival exit gate. Runs on the emulator against the real Room engine and proves the
 * durable send queue survives process death: three DM sends are persisted to the on-disk `pending_send`
 * table, the database is closed (simulating the process exiting), a fresh database + queue are rebuilt
 * from the same file, the connection reaches `.ready`, and the drain replays all three sends in their
 * original sequence order through a fresh session. The message rows are held in the in-memory store across
 * the rebuild (their own Room persistence is covered by the DB round-trip suite); the claim under test is
 * that the persisted `PendingSend` rows survive the restart and drive an ordered replay.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("MagicNumber") // Fixed packet bytes and timing bounds are test inputs.
class SendQueueRestartSurvivalInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "sendqueue-restart-${System.nanoTime()}.db"
    private val radioID = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    private var db: MeshDatabase? = null
    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        scope?.cancel()
        db?.close()
        context.getDatabasePath(dbName).delete()
    }

    /** A session that records the text of each DM it is asked to send, in order, and acknowledges instantly. */
    private class RecordingSession : ChatSession {
        private val eventsFlow = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 16)
        override val events: SharedFlow<MeshEvent> = eventsFlow.asSharedFlow()
        val sentTexts: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun currentSelfPublicKey(): ByteArray = ByteArray(32) { 1 }

        override suspend fun sendDirectMessage(
            destination: ByteArray,
            text: String,
            timestamp: Long,
            attempt: Int,
        ): MessageSentInfo {
            sentTexts.add(text)
            return MessageSentInfo(route = 0, expectedAck = byteArrayOf(1, 2, 3, 4), suggestedTimeoutMs = 5000)
        }

        override suspend fun sendChannelMessage(
            channelIndex: Int,
            text: String,
            timestamp: Long,
            floodScope: ResolvedFloodScope,
        ) = Unit

        override suspend fun sendChannelMessage(channelIndex: Int, text: String, timestamp: Long) = Unit
        override suspend fun resetPath(publicKey: ByteArray) = Unit
        override suspend fun getContact(publicKey: ByteArray): MeshContact? = null
        override suspend fun waitForAcknowledgement(code: ByteArray, timeout: kotlin.time.Duration): Boolean = true
    }

    private class Ids {
        private var n = 0
        fun next(): Uuid {
            n += 1
            return Uuid.normalized("00000000-0000-0000-0000-" + n.toString().padStart(12, '0'))
        }
    }

    private fun buildMessageService(
        session: ChatSession,
        store: MessageStore,
        scope: CoroutineScope,
        ids: Ids,
    ): MessageService = MessageService(
        session,
        store,
        scope,
        MessageServiceIdentity({ 1_700_000_000.0 }, ids::next),
    )

    private fun contact(id: Uuid): ContactDto = ContactDto(
        id = id,
        radioID = radioID,
        publicKey = ByteArray(32) { 7 },
        name = "Contact",
        typeRawValue = ContactType.CHAT.raw,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )

    @Test
    fun persistedSendsSurviveRestartAndReplayInOrder() = runBlocking {
        val messageStore = InMemoryMessageStore()
        val contacts = mutableMapOf<Uuid, ContactDto>()
        val texts = listOf("first message", "second message", "third message")
        val messageIDs = mutableListOf<Uuid>()

        // Phase 1: create the Contact + Message + PendingSend triple for three sends and persist the
        // PendingSend rows to the on-disk table, then close the database (process death).
        run {
            val db1 = meshDatabase(context, Dispatchers.IO, dbName)
            db = db1
            val ids = Ids()
            val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val messageService = buildMessageService(RecordingSession(), messageStore, scope1, ids)
            val roomStore1 = RoomPendingSendStore(db1.pendingSendDao())

            texts.forEachIndexed { index, text ->
                val contactDto = contact(ids.next())
                contacts[contactDto.id] = contactDto
                val message = messageService.createPendingMessage(text, contactDto)
                messageIDs.add(message.id)
                roomStore1.insertPendingSendAssigningSequence(
                    DirectMessageEnvelope(message.id, contactDto.id)
                        .toPendingSendDto(ids.next(), radioID, sequence = index + 1, 1_700_000_000.0, attemptCount = 0),
                )
            }
            scope1.cancel()
            db1.close()
        }

        // Phase 2: reopen the database from the same file, rebuild the queue, reconnect, and drain.
        val db2 = meshDatabase(context, Dispatchers.IO, dbName)
        db = db2
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = scope2
        val session2 = RecordingSession()
        val messageService2 = buildMessageService(session2, messageStore, scope2, Ids())
        val roomStore2 = RoomPendingSendStore(db2.pendingSendDao())
        val queueStore = DefaultSendQueueStore(roomStore2, messageStore) { contacts[it] }

        val surviving = roomStore2.fetchPendingSends(radioID)
        assertEquals("all three PendingSend rows survived the restart on disk", 3, surviving.size)

        val queue = ChatSendQueueService(
            radioID = radioID,
            collaborators = ChatSendQueueCollaborators(
                store = queueStore,
                messageService = messageService2,
                channelResolver = { true },
                scope = scope2,
                now = { 1_700_000_000.0 },
                newPendingSendId = Ids()::next,
            ),
        )
        queue.observeConnectionState(DeviceConnectionState.READY, emptyFlow())
        queue.hydrate()

        withTimeout(20.seconds) { queue.awaitDrainCompletion() }

        assertEquals("the drain replays every persisted send in sequence order", texts, session2.sentTexts.toList())
        assertTrue("every drained row is deleted", roomStore2.fetchPendingSends(radioID).isEmpty())
    }
}
