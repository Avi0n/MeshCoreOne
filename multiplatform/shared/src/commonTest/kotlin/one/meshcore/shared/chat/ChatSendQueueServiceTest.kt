package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Mirrors the Swift `ChatSendQueueServiceTests`: hydrate replay + radio scoping + once-per-instance latch,
 * the transient-vs-terminal DM drain broadcast contract, the error classifiers, and the connection-state
 * `.ready`-edge trigger. The disabled Swift real-time cases (30s park timeout) become deterministic here
 * under the test scheduler's virtual clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendQueueServiceTest {
    private companion object {
        const val NOW: Double = 1_700_000_000.0
        val RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")

        // Tight backoff so a transient send fails within a fraction of a second of virtual time, well under
        // the 30s park timeout, letting a bounded advanceTimeBy observe the parked steady state.
        val FAST_BACKOFF = MessageServiceConfig(
            poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelaySeconds = 0.01),
        )
    }

    private val pids = PendingSendIds()

    private fun dmRowFor(messageID: Uuid, contactID: Uuid, radioID: Uuid, sequence: Int): PendingSendDto =
        DirectMessageEnvelope(messageID, contactID)
            .toPendingSendDto(pids.next(), radioID, sequence, NOW, attemptCount = 0)

    private fun dmRow(messageID: Uuid, radioID: Uuid, sequence: Int): PendingSendDto =
        dmRowFor(messageID, ChatFixtures.CONTACT, radioID, sequence)

    private fun messageService(session: ChatSession, store: FakeSendQueueStore, scope: CoroutineScope): MessageService =
        MessageService(
            session,
            store.messages,
            scope,
            MessageServiceIdentity({ NOW }, SequentialIds()::next),
            FAST_BACKOFF,
        )

    private fun queueService(
        store: FakeSendQueueStore,
        service: MessageService,
        scope: CoroutineScope,
        resolver: FakeChannelResolver = FakeChannelResolver(),
    ): ChatSendQueueService = ChatSendQueueService(
        radioID = RADIO,
        collaborators = ChatSendQueueCollaborators(
            store = store,
            messageService = service,
            channelResolver = resolver,
            scope = scope,
            now = { NOW },
            newPendingSendId = pids::next,
        ),
    )

    private class DmHarness(
        val store: FakeSendQueueStore,
        val session: FakeChatSession,
        val messageService: MessageService,
        val queue: ChatSendQueueService,
        val messageID: Uuid,
        val radioID: Uuid,
    )

    private suspend fun buildDmHarness(scope: CoroutineScope): DmHarness {
        val store = FakeSendQueueStore()
        val session = FakeChatSession()
        val service = messageService(session, store, scope)

        val contact = ChatFixtures.contact("Test Contact")
        store.contacts[contact.id] = contact
        val pending = service.createPendingMessage("Hello", contact)

        store.pending.upsertPendingSend(dmRowFor(pending.id, contact.id, RADIO, sequence = 1))

        val queue = queueService(store, service, scope)
        return DmHarness(store, session, service, queue, pending.id, RADIO)
    }

    private suspend fun attemptCountOf(harness: DmHarness): Int? =
        harness.store.fetchPendingSends(harness.radioID).firstOrNull { it.messageID == harness.messageID }?.attemptCount

    // MARK: - Hydrate

    @Test
    fun `hydrate replays persisted rows and drains a deleted-contact row via the drop path`() = runTest {
        val store = FakeSendQueueStore()
        val service = messageService(FakeChatSession(), store, backgroundScope)
        val queue = queueService(store, service, backgroundScope)
        // A row whose contact was never registered: the drain hits the missing-contact drop path.
        val messageID = SequentialIds().next()
        store.pending.upsertPendingSend(dmRow(messageID, RADIO, sequence = 1))
        assertEquals(1, store.fetchPendingSends(RADIO).size)

        queue.hydrate()
        queue.awaitDrainCompletion()

        assertTrue(store.fetchPendingSends(RADIO).isEmpty(), "hydrate + drop-path drain clears the row")
    }

    @Test
    fun `hydrate processes only the service's own radio rows`() = runTest {
        val store = FakeSendQueueStore()
        val radioB = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
        val service = messageService(FakeChatSession(), store, backgroundScope)
        val queue = queueService(store, service, backgroundScope)
        val ids = SequentialIds()
        store.pending.upsertPendingSend(dmRow(ids.next(), RADIO, sequence = 1))
        val foreignMessage = ids.next()
        store.pending.upsertPendingSend(dmRow(foreignMessage, radioB, sequence = 1))

        queue.hydrate()
        queue.awaitDrainCompletion()

        assertTrue(store.fetchPendingSends(RADIO).isEmpty(), "own-radio rows drain")
        val foreignRows = store.fetchPendingSends(radioB)
        assertEquals(1, foreignRows.size, "another radio's rows are untouched")
        assertEquals(foreignMessage, foreignRows.first().messageID)
    }

    @Test
    fun `hydrate fetch returns rows in sequence order`() = runTest {
        val store = InMemoryPendingSendStore()
        val ids = SequentialIds()
        val a = ids.next()
        val b = ids.next()
        val c = ids.next()
        store.insertPendingSendAssigningSequence(dmRow(a, RADIO, sequence = 0))
        store.insertPendingSendAssigningSequence(dmRow(b, RADIO, sequence = 0))
        store.insertPendingSendAssigningSequence(dmRow(c, RADIO, sequence = 0))

        assertEquals(listOf(a, b, c), store.fetchPendingSends(RADIO).map { it.messageID })
    }

    @Test
    fun `hydrate runs once per service instance`() = runTest {
        val store = FakeSendQueueStore()
        val service = messageService(FakeChatSession(), store, backgroundScope)
        val queue = queueService(store, service, backgroundScope)
        val ids = SequentialIds()
        store.pending.upsertPendingSend(dmRow(ids.next(), RADIO, sequence = 1))
        queue.hydrate()
        queue.awaitDrainCompletion()
        assertTrue(store.fetchPendingSends(RADIO).isEmpty())

        // A row inserted after the first hydrate must not be re-enqueued by a second hydrate.
        store.pending.upsertPendingSend(dmRow(ids.next(), RADIO, sequence = 2))
        queue.hydrate()
        queue.awaitDrainCompletion()
        assertEquals(1, store.fetchPendingSends(RADIO).size, "second hydrate is a no-op")
    }

    // MARK: - Transient vs terminal

    @Test
    fun `transient DM send error preserves the persisted row while parked`() = runTest {
        val harness = buildDmHarness(backgroundScope)
        harness.session.directSendError = { ChatDeviceException(FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL) }

        harness.queue.hydrate()
        advanceTimeBy(2.seconds)

        val rows = harness.store.fetchPendingSends(harness.radioID)
        assertEquals(1, rows.size, "transient error must not delete the parked row")
        assertEquals(1, rows.first().attemptCount, "top-of-drain bump persisted before the send failed")
        assertEquals(MessageStatus.PENDING, harness.store.messages.fetchMessage(harness.messageID)?.status)
    }

    @Test
    fun `transient DM send error must not broadcast failed`() = runTest {
        val harness = buildDmHarness(backgroundScope)
        val events = harness.messageService.statusEvents()
        harness.session.directSendError = { ChatDeviceException(FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL) }

        harness.queue.hydrate()
        advanceTimeBy(2.seconds)

        assertTrue(events.drain().failedIds().isEmpty(), "transient errors must not broadcast .failed")
    }

    @Test
    fun `terminal DM send error broadcasts failed exactly once`() = runTest {
        val harness = buildDmHarness(backgroundScope)
        val events = harness.messageService.statusEvents()
        harness.session.directSendError = { ChatDeviceException(BridgeErrorCode.INVALID_INPUT.code) }

        harness.queue.hydrate()
        harness.queue.awaitDrainCompletion()

        val failed = events.drain().failedIds()
        assertEquals(listOf(harness.messageID), failed, "terminal errors broadcast .failed once, carrying the id")
        assertTrue(harness.store.fetchPendingSends(harness.radioID).isEmpty(), "onError deletes the row")
    }

    // MARK: - Connection-state trigger

    @Test
    fun `observeConnectionState fires the trigger for an already-ready initial state`() = runTest {
        val harness = buildDmHarness(backgroundScope)
        harness.session.directSendError = { ChatDeviceException(FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL) }
        harness.queue.hydrate()
        advanceTimeBy(2.seconds)
        assertEquals(1, attemptCountOf(harness))

        harness.queue.observeConnectionState(DeviceConnectionState.READY, emptyFlow())
        advanceTimeBy(2.seconds)

        assertEquals(2, attemptCountOf(harness), "an already-ready initial state wakes the parked drain")
    }

    @Test
    fun `observeConnectionState keeps a drain parked through connected and wakes it on ready`() = runTest {
        val harness = buildDmHarness(backgroundScope)
        harness.session.directSendError = { ChatDeviceException(FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL) }
        val states = MutableSharedFlow<DeviceConnectionState>(extraBufferCapacity = 8)
        harness.queue.observeConnectionState(DeviceConnectionState.CONNECTED, states)

        harness.queue.hydrate()
        advanceTimeBy(2.seconds)
        assertEquals(1, attemptCountOf(harness))

        states.emit(DeviceConnectionState.CONNECTED)
        advanceTimeBy(2.seconds)
        assertEquals(1, attemptCountOf(harness), ".connected must not wake the drain during the sync window")

        states.emit(DeviceConnectionState.READY)
        advanceTimeBy(2.seconds)
        assertEquals(2, attemptCountOf(harness), "entering .ready wakes the parked drain")
    }

    @Test
    fun `connection-state ramp fires the trigger exactly once`() = runTest {
        val harness = buildDmHarness(backgroundScope)
        harness.session.directSendError = { ChatDeviceException(FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL) }
        val states = MutableSharedFlow<DeviceConnectionState>(extraBufferCapacity = 8)
        harness.queue.observeConnectionState(DeviceConnectionState.DISCONNECTED, states)

        harness.queue.hydrate()
        advanceTimeBy(2.seconds)
        assertEquals(1, attemptCountOf(harness))

        states.emit(DeviceConnectionState.DISCONNECTED)
        states.emit(DeviceConnectionState.CONNECTED)
        states.emit(DeviceConnectionState.SYNCING)
        states.emit(DeviceConnectionState.READY)
        advanceTimeBy(2.seconds)

        assertEquals(2, attemptCountOf(harness), "the connected ramp crosses into .ready once, waking once")
    }

    // MARK: - Error classifiers (pure)

    @Test
    fun `isTransientDirectMessageError unwraps sessionError on deviceError(3)`() {
        assertTrue(isTransientDirectMessageError(MessageServiceException.SessionError(ChatDeviceException(3))))
    }

    @Test
    fun `isTransientDirectMessageError treats deviceError(2) as terminal`() {
        assertFalse(isTransientDirectMessageError(MessageServiceException.SessionError(ChatDeviceException(2))))
    }

    @Test
    fun `isTransientChannelMessageError unwraps sessionError on deviceError(2)`() {
        assertTrue(isTransientChannelMessageError(MessageServiceException.SessionError(ChatDeviceException(2))))
    }

    @Test
    fun `isTransientChannelMessageError treats deviceError(3) as terminal`() {
        assertFalse(isTransientChannelMessageError(MessageServiceException.SessionError(ChatDeviceException(3))))
    }

    @Test
    fun `isChannelMessageNotFound matches raw and wrapped deviceError(2) and rejects others`() {
        val code = FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND
        assertTrue(isChannelMessageNotFound(ChatDeviceException(code)))
        assertTrue(isChannelMessageNotFound(MessageServiceException.SessionError(ChatDeviceException(code))))
        assertFalse(isChannelMessageNotFound(ChatDeviceException(FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL)))
        assertFalse(isChannelMessageNotFound(MeshBridgeException(BridgeErrorCode.TIMEOUT, "timeout")))
    }

    @Test
    fun `PersistFailed and NotConnected carry non-empty messages`() {
        assertTrue(ChatSendQueueServiceException.NotConnected().message?.isNotEmpty() == true)
        assertTrue(ChatSendQueueServiceException.PersistFailed(RuntimeException("x")).message?.isNotEmpty() == true)
    }
}
