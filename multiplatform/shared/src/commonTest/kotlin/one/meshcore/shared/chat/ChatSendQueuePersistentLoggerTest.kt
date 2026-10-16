package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The radio-drop dump must carry send-queue hydrate and drain lines. iOS writes these through
 * `PersistentLogger(category: "ChatSendQueueService")`; these tests pin the same strings on Kotlin.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendQueuePersistentLoggerTest {
    @Test
    fun `hydrate records begin and complete`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = FakeSendQueueStore()
        val queue = queueWithLogger(this, recorded, store)
        store.pending.upsertPendingSend(
            DirectMessageEnvelope(MESSAGE, CONTACT)
                .toPendingSendDto(pids.next(), RADIO, sequence = 1, NOW, attemptCount = 0),
        )

        queue.hydrate()
        queue.shutdown()

        val begin = recorded.single { it.message.contains("hydrate begin") }
        val complete = recorded.single { it.message.contains("hydrate complete") }
        assertEquals(DebugLogLevel.INFO, begin.level)
        assertEquals("ChatSendQueueService", begin.category)
        assertTrue(begin.message.contains(RADIO.value))
        assertTrue(complete.message.contains("count=1"))
        assertTrue(complete.message.contains(RADIO.value))
    }

    @Test
    fun `hydrate fetch failure records Hydration failed`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = FakeSendQueueStore().apply {
            fetchPendingSendsError = { IllegalStateException("disk") }
        }
        val queue = queueWithLogger(this, recorded, store)

        queue.hydrate()

        val line = recorded.single { it.message.contains("Hydration failed") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("disk"))
        assertTrue(recorded.none { it.message.contains("hydrate complete") })
    }

    @Test
    fun aCancelledHydrateKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = FakeSendQueueStore().apply { fetchPendingSendsError = { stopped } }
        val queue = queueWithLogger(this, recorded, store)

        val cancelled = assertFailsWith<CancellationException> { queue.hydrate() }

        assertSame(stopped, cancelled)
        assertTrue(recorded.none { it.message.contains("Hydration failed") })
        assertTrue(recorded.none { it.message.contains("hydrate complete") })
    }

    @Test
    fun aHydrateErrorIsLoggedAndDoesNotFinish() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = FakeSendQueueStore().apply { fetchPendingSendsError = { throw HydrateProbeError() } }
        val queue = queueWithLogger(this, recorded, store)

        queue.hydrate()

        val line = recorded.single { it.message.contains("Hydration failed") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("probe"))
        assertTrue(recorded.none { it.message.contains("hydrate complete") })
    }

    @Test
    fun `persist failure records Persisting envelope failed`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = FakeSendQueueStore().apply {
            insertError = { IllegalStateException("disk full") }
        }
        val queue = queueWithLogger(this, recorded, store)

        assertFailsWith<ChatSendQueueServiceException.PersistFailed> {
            queue.enqueueDM(DirectMessageEnvelope(MESSAGE, CONTACT))
        }

        val line = recorded.single { it.message.contains("Persisting envelope failed") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("disk full"))
    }

    @Test
    fun `drain abandoned when the pending row is gone`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = FakeSendQueueStore().apply { hasPendingSendOverride = false }
        store.contacts[CONTACT] = ChatFixtures.contact("Alice", id = CONTACT)
        val queue = queueWithLogger(this, recorded, store)

        queue.enqueueDM(DirectMessageEnvelope(MESSAGE, CONTACT))
        queue.awaitDrainCompletion()

        val line = recorded.single { it.message.contains("PendingSendGone") }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertTrue(line.message.contains("DM drain abandoned"))
        assertTrue(line.message.contains(MESSAGE.value))
    }

    @Test
    fun `a terminal DM drain records the iOS outcome lines`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = FakeSendQueueStore()
        store.contacts[CONTACT] = ChatFixtures.contact("Alice", id = CONTACT)
        val queue = queueWithLogger(this, recorded, store)

        queue.enqueueDM(DirectMessageEnvelope(MESSAGE, CONTACT))
        queue.awaitDrainCompletion()

        val terminal = recorded.single { it.message.startsWith("DM drain terminal") }
        assertEquals(DebugLogLevel.INFO, terminal.level)
        assertTrue(terminal.message.contains(MESSAGE.value))
        val drained = recorded.single { it.message.startsWith("DM queue drained with error") }
        assertEquals(DebugLogLevel.ERROR, drained.level)
    }

    @Test
    fun `an onError persist fault records the dump and still drains`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = FakeSendQueueStore().apply {
            deletePendingSendsError = { IllegalStateException("db locked") }
        }
        store.contacts[CONTACT] = ChatFixtures.contact("Alice", id = CONTACT)
        val queue = queueWithLogger(this, recorded, store)

        queue.enqueueDM(DirectMessageEnvelope(MESSAGE, CONTACT))
        queue.awaitDrainCompletion()

        val line = recorded.single { it.message.contains("Failed to persist send-queue drain state") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("db locked"))
        assertTrue(recorded.any { it.message.startsWith("DM queue drained with error") })
    }

    private val pids = PendingSendIds()

    private fun queueWithLogger(
        scope: kotlinx.coroutines.CoroutineScope,
        recorded: MutableList<DebugLogEntryDto>,
        store: FakeSendQueueStore,
        config: ChatSendQueueConfig = ChatSendQueueConfig(),
    ): ChatSendQueueService = ChatSendQueueService(
        radioID = RADIO,
        collaborators = ChatSendQueueCollaborators(
            store = store,
            messageService = MessageService(
                FakeChatSession(),
                store.messages,
                scope,
                MessageServiceIdentity({ NOW }, SequentialIds()::next),
            ),
            channelResolver = FakeChannelResolver(),
            scope = scope,
            now = { NOW },
            newPendingSendId = pids::next,
        ),
        edges = ChatSendQueueEdges(
            config = config,
            logger = PersistentLogger(
                scope = scope,
                category = "ChatSendQueueService",
                record = { recorded += it },
            ),
        ),
    )

    private companion object {
        const val NOW: Double = 1_700_000_000.0
        val RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val CONTACT: Uuid = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
        val MESSAGE: Uuid = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")
    }
}

private class HydrateProbeError : Error("probe")
