package one.meshcore.shared.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.meshcore.ContactMessage
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * The W2-S4 live-receive exit gate. Runs on the emulator: a fake session pushes a contact-message frame
 * onto the live event stream, the [MessagePoller]'s monitor resolves the sender, and
 * [MessageIngestPipeline] persists the row and broadcasts a real-time event. Proves the ingest layer
 * works end-to-end on the Android runtime (real coroutine dispatchers and flow delivery) with the
 * in-memory store standing in for Room, whose own persistence is covered by the DB round-trip suite.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("MagicNumber") // Packet payload bytes and test timing values are deliberate fixtures.
class MessageIngestLiveReceiveInstrumentedTest {
    private val radioID = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val contactID = Uuid.normalized("B0000000-0000-0000-0000-000000000002")
    private val senderPrefix = byteArrayOf(1, 2, 3, 4, 5, 6)

    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        scope?.cancel()
    }

    /** A session that pushes live events and reports an empty backlog. */
    private class LiveSession : IngestSession {
        private val eventsFlow = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 16)
        override val events: SharedFlow<MeshEvent> = eventsFlow.asSharedFlow()
        override suspend fun getMessage(): GetMessageResult = GetMessageResult.NoMoreMessages
        suspend fun emit(event: MeshEvent) = eventsFlow.emit(event)

        /** Suspends until the live monitor has subscribed, so an emit is never lost before collection. */
        suspend fun awaitSubscriber() = eventsFlow.subscriptionCount.first { it > 0 }
    }

    private class Ids {
        private var n = 0
        fun next(): Uuid {
            n += 1
            return Uuid.normalized("00000000-0000-0000-0000-" + n.toString().padStart(12, '0'))
        }
    }

    @Test
    fun liveContactMessagePersistsAndBroadcasts() = runBlocking {
        val store = InMemoryIngestStore()
        store.seedContact(
            senderPrefix,
            ResolvedContact(id = contactID, displayName = "Alice", isBlocked = false, isMuted = false),
        )
        val broadcaster = IncomingMessageBroadcaster()
        val session = LiveSession()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = testScope

        val pipeline = MessageIngestPipeline(
            collaborators = MessageIngestCollaborators(
                radioID = radioID,
                store = store,
                blockedSenders = BlockedSendersCache(),
                broadcaster = broadcaster,
                selfNodeName = { "" },
                now = { 1_700_000_000.0 },
                newMessageId = Ids()::next,
            ),
        )
        val poller = MessagePoller(
            radioID = radioID,
            session = session,
            store = store,
            pipeline = pipeline,
            scope = testScope,
            edges = MessagePollerEdges(now = { 1_700_000_000.0 }),
        )

        val received = broadcaster.subscribe()
        poller.startEventMonitoring()
        withTimeout(10.seconds) { session.awaitSubscriber() }

        val frame = ContactMessage(
            senderPublicKeyPrefix = senderPrefix,
            pathLength = 3,
            textType = TextType.PLAIN.raw,
            senderTimestamp = 1_700_000_000L,
            text = "live from the mesh",
            snr = -6.0,
        )
        session.emit(MeshEvent.ContactMessageReceived(frame))

        val event = withTimeout(10.seconds) { received.receive() }
        assertTrue("broadcast a direct-message event", event is IncomingMessageEvent.DirectMessageReceived)

        withTimeout(10.seconds) {
            while (store.savedMessages().isEmpty()) delay(20)
        }
        val saved = store.savedMessages().single()
        assertEquals("live from the mesh", saved.text)
        assertEquals(contactID, saved.contactID)
    }
}
