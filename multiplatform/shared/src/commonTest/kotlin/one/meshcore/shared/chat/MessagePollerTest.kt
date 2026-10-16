package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.ChannelDatagram
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.TextType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `MessagePollingService` drain semantics: the backlog loop drains until noMoreMessages,
 * a shared block anchor stamps every drained message's sort date, datagrams drain without counting, the
 * live monitor ingests pushed messages, the isPolling gate suppresses live events during a drain, and the
 * CLI/signed textType routing bypasses the W2 pipeline.
 */
class MessagePollerTest {
    private val future = INGEST_NOW_SECONDS.toLong()

    private fun buildPoller(
        session: FakeIngestSession,
        store: IngestStore,
        scope: CoroutineScope,
        pipeline: MessageIngestPipeline,
        edges: MessagePollerEdges = MessagePollerEdges(now = { INGEST_NOW_SECONDS }),
    ): MessagePoller = MessagePoller(
        radioID = ChatFixtures.RADIO,
        session = session,
        store = store,
        pipeline = pipeline,
        scope = scope,
        edges = edges,
    )

    /** An [IngestStore] whose save throws for one message's text, standing in for a Room fault. */
    private class FaultingIngestStore(
        private val delegate: InMemoryIngestStore,
        private val faultText: String,
        private val fault: (String) -> Throwable,
    ) : IngestStore by delegate {
        override suspend fun saveMessage(message: one.meshcore.shared.backup.MessageDto) {
            if (message.text == faultText) throw fault(message.text)
            delegate.saveMessage(message)
        }
    }

    private fun buildPipeline(store: IngestStore, now: () -> Double = { INGEST_NOW_SECONDS }) = MessageIngestPipeline(
        collaborators = MessageIngestCollaborators(
            radioID = ChatFixtures.RADIO,
            store = store,
            blockedSenders = BlockedSendersCache(),
            broadcaster = IncomingMessageBroadcaster(),
            selfNodeName = { "" },
            now = now,
            newMessageId = SequentialIds()::next,
        ),
    )

    @Test
    fun pollAllMessagesDrainsUntilNoMore() = runTest {
        val store = InMemoryIngestStore()
        store.seedContact(TEST_SENDER_PREFIX, resolvedContact())
        store.seedChannel(ChatFixtures.RADIO, channelIndex = 3, resolvedChannel(index = 3))
        val session = FakeIngestSession()
        session.queue(
            GetMessageResult.ContactMessageReceived(contactMessage("first", future)),
            GetMessageResult.ChannelMessageReceived(channelMessageFrame("Bob: second", future + 1, channelIndex = 3)),
            GetMessageResult.ContactMessageReceived(contactMessage("third", future + 2)),
        )
        val poller = buildPoller(session, store, backgroundScope, buildPipeline(store))

        val count = poller.pollAllMessages()

        assertEquals(3, count)
        assertEquals(3, store.savedMessages().size)
    }

    @Test
    fun unknownSenderMaterializesFromAPendingAdvert() = runTest {
        val store = InMemoryIngestStore()
        val session = FakeIngestSession()
        session.queue(
            GetMessageResult.ContactMessageReceived(contactMessage("debounce dm", future)),
        )
        val materialized = resolvedContact(displayName = "DebounceNode")
        var prefixes = 0
        val poller = buildPoller(
            session,
            store,
            backgroundScope,
            buildPipeline(store),
            edges = MessagePollerEdges(
                now = { INGEST_NOW_SECONDS },
                materializePendingContact = { prefix ->
                    prefixes += 1
                    assertTrue(prefix.contentEquals(TEST_SENDER_PREFIX))
                    materialized
                },
            ),
        )

        val count = poller.pollAllMessages()

        assertEquals(1, count)
        assertEquals(1, prefixes)
        assertEquals(listOf("debounce dm"), store.savedMessages().map { it.text })
        assertEquals(materialized.id, store.savedMessages().single().contactID)
    }

    @Test
    fun oneFaultingBacklogMessageDoesNotAbortTheDrain() = runTest {
        val backing = InMemoryIngestStore()
        backing.seedContact(TEST_SENDER_PREFIX, resolvedContact())
        val store = FaultingIngestStore(backing, faultText = "poison") { IllegalStateException("store fault: $it") }
        val session = FakeIngestSession()
        val failures = mutableListOf<Throwable>()
        session.queue(
            GetMessageResult.ContactMessageReceived(contactMessage("poison", future)),
            GetMessageResult.ContactMessageReceived(contactMessage("after", future + 1)),
        )
        val poller = buildPoller(
            session,
            store,
            backgroundScope,
            buildPipeline(store),
            edges = MessagePollerEdges(
                now = { INGEST_NOW_SECONDS },
                onIngestFailure = { failures += it },
            ),
        )

        val count = poller.pollAllMessages()

        assertEquals(2, count)
        assertEquals(listOf("after"), backing.savedMessages().map { it.text })
        assertEquals(1, failures.size)
    }

    @Test
    fun datagramsDrainWithoutCounting() = runTest {
        val store = InMemoryIngestStore()
        store.seedContact(TEST_SENDER_PREFIX, resolvedContact())
        val session = FakeIngestSession()
        session.queue(
            GetMessageResult.ContactMessageReceived(contactMessage("real", future)),
            GetMessageResult.ChannelDataReceived(
                ChannelDatagram(channelIndex = 0, pathLength = 1, dataType = 0, data = byteArrayOf(1), snr = -1.0),
            ),
        )
        val poller = buildPoller(session, store, backgroundScope, buildPipeline(store))

        val count = poller.pollAllMessages()

        assertEquals(1, count)
        assertEquals(1, store.savedMessages().size)
    }

    @Test
    fun blockAnchorStampsSharedSortDateAcrossBacklog() = runTest {
        val store = InMemoryIngestStore()
        store.seedContact(TEST_SENDER_PREFIX, resolvedContact())
        val session = FakeIngestSession()
        session.queue(
            GetMessageResult.ContactMessageReceived(contactMessage("a", future)),
            GetMessageResult.ContactMessageReceived(contactMessage("b", future + 1)),
        )
        // A clock that advances on each read: the anchor is captured once, message createdAt keeps moving.
        var tick = INGEST_NOW_SECONDS
        val clock = { tick.also { tick += 1.0 } }
        val poller = buildPoller(
            session,
            store,
            backgroundScope,
            buildPipeline(store, clock),
            edges = MessagePollerEdges(now = clock),
        )

        poller.pollAllMessages()

        val saved = store.savedMessages()
        assertEquals(2, saved.size)
        assertEquals(saved[0].sortDate, saved[1].sortDate)
        assertTrue(saved[0].createdAt != saved[1].createdAt)
    }

    @Test
    fun liveEventIsIngestedWhenNotPolling() = runTest {
        val store = InMemoryIngestStore()
        store.seedContact(TEST_SENDER_PREFIX, resolvedContact())
        val session = FakeIngestSession()
        val poller = buildPoller(session, store, backgroundScope, buildPipeline(store))
        poller.startEventMonitoring()
        testScheduler.runCurrent()

        session.emit(MeshEvent.ContactMessageReceived(contactMessage("live", future)))
        testScheduler.runCurrent()

        assertEquals(listOf("live"), store.savedMessages().map { it.text })
    }

    @Test
    fun isPollingGateSuppressesConcurrentLiveEvent() = runTest {
        val store = InMemoryIngestStore()
        store.seedContact(TEST_SENDER_PREFIX, resolvedContact())
        val session = FakeIngestSession()
        val gate = CompletableDeferred<Unit>()
        session.getMessageGate = gate
        session.queue(GetMessageResult.ContactMessageReceived(contactMessage("backlog", future)))
        val poller = buildPoller(session, store, backgroundScope, buildPipeline(store))
        poller.startEventMonitoring()
        testScheduler.runCurrent()

        val drain = backgroundScope.async { poller.pollAllMessages() }
        testScheduler.runCurrent() // drain starts, isPolling = true, suspended on the gate

        session.emit(MeshEvent.ContactMessageReceived(contactMessage("live-during-poll", future + 1)))
        testScheduler.runCurrent() // monitor observes the event and skips it (isPolling true)

        gate.complete(Unit)
        val count = drain.await()

        assertEquals(1, count)
        assertEquals(listOf("backlog"), store.savedMessages().map { it.text })
    }

    @Test
    fun oneFaultingMessageDoesNotEndTheLiveCollector() = runTest {
        val backing = InMemoryIngestStore()
        backing.seedContact(TEST_SENDER_PREFIX, resolvedContact())
        val store = FaultingIngestStore(backing, faultText = "poison") { IllegalStateException("store fault: $it") }
        val session = FakeIngestSession()
        val failures = mutableListOf<Throwable>()
        val poller = buildPoller(
            session,
            store,
            backgroundScope,
            buildPipeline(store),
            edges = MessagePollerEdges(
                now = { INGEST_NOW_SECONDS },
                onIngestFailure = { failures += it },
            ),
        )
        poller.startEventMonitoring()
        testScheduler.runCurrent()

        session.emit(MeshEvent.ContactMessageReceived(contactMessage("poison", future)))
        testScheduler.runCurrent()
        session.emit(MeshEvent.ContactMessageReceived(contactMessage("after", future + 1)))
        testScheduler.runCurrent()

        assertEquals(
            listOf("after"),
            backing.savedMessages().map { it.text },
            "the message after a store fault must still be ingested",
        )
        assertEquals(1, failures.size)
    }

    @Test
    fun cancellationStillEndsTheLiveCollector() = runTest {
        val backing = InMemoryIngestStore()
        backing.seedContact(TEST_SENDER_PREFIX, resolvedContact())
        val store = FaultingIngestStore(backing, faultText = "teardown") { CancellationException("teardown") }
        val session = FakeIngestSession()
        val failures = mutableListOf<Throwable>()
        val poller = buildPoller(
            session,
            store,
            backgroundScope,
            buildPipeline(store),
            edges = MessagePollerEdges(
                now = { INGEST_NOW_SECONDS },
                onIngestFailure = { failures += it },
            ),
        )
        poller.startEventMonitoring()
        testScheduler.runCurrent()

        session.emit(MeshEvent.ContactMessageReceived(contactMessage("teardown", future)))
        testScheduler.runCurrent()
        session.emit(MeshEvent.ContactMessageReceived(contactMessage("after", future + 1)))
        testScheduler.runCurrent()

        assertTrue(backing.savedMessages().isEmpty(), "cancellation tears the collector down instead of resuming")
        assertTrue(failures.isEmpty(), "cancellation is not reported as an ingest fault")
    }

    @Test
    fun anIngestErrorAbortsTheBacklogDrain() = runTest {
        val backing = InMemoryIngestStore()
        backing.seedContact(TEST_SENDER_PREFIX, resolvedContact())
        val fault = IngestProbeError()
        val store = FaultingIngestStore(backing, faultText = "poison") { fault }
        val session = FakeIngestSession()
        val failures = mutableListOf<Throwable>()
        session.queue(
            GetMessageResult.ContactMessageReceived(contactMessage("poison", future)),
            GetMessageResult.ContactMessageReceived(contactMessage("after", future + 1)),
        )
        val poller = buildPoller(
            session,
            store,
            backgroundScope,
            buildPipeline(store),
            edges = MessagePollerEdges(
                now = { INGEST_NOW_SECONDS },
                onIngestFailure = { failures += it },
            ),
        )

        val thrown = assertFailsWith<IngestProbeError> { poller.pollAllMessages() }
        assertSame(fault, thrown)

        assertTrue(backing.savedMessages().isEmpty())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun aCancelledBacklogIngestRethrows() = runTest {
        val backing = InMemoryIngestStore()
        backing.seedContact(TEST_SENDER_PREFIX, resolvedContact())
        val cancelled = CancellationException("teardown")
        val store = FaultingIngestStore(backing, faultText = "teardown") { cancelled }
        val session = FakeIngestSession()
        val failures = mutableListOf<Throwable>()
        session.queue(
            GetMessageResult.ContactMessageReceived(contactMessage("teardown", future)),
            GetMessageResult.ContactMessageReceived(contactMessage("after", future + 1)),
        )
        val poller = buildPoller(
            session,
            store,
            backgroundScope,
            buildPipeline(store),
            edges = MessagePollerEdges(
                now = { INGEST_NOW_SECONDS },
                onIngestFailure = { failures += it },
            ),
        )

        val thrown = assertFailsWith<CancellationException> { poller.pollAllMessages() }
        assertSame(cancelled, thrown)
        assertTrue(backing.savedMessages().isEmpty())
        assertTrue(failures.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anIngestErrorEndsTheLiveCollector() = runTest {
        val backing = InMemoryIngestStore()
        backing.seedContact(TEST_SENDER_PREFIX, resolvedContact())
        val fault = IngestProbeError()
        val store = FaultingIngestStore(backing, faultText = "poison") { fault }
        val session = FakeIngestSession()
        val failures = mutableListOf<Throwable>()
        val thrown = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() +
                StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> thrown += error },
        )
        val poller = buildPoller(
            session,
            store,
            scope,
            buildPipeline(store),
            edges = MessagePollerEdges(
                now = { INGEST_NOW_SECONDS },
                onIngestFailure = { failures += it },
            ),
        )
        poller.startEventMonitoring()
        testScheduler.runCurrent()

        session.emit(MeshEvent.ContactMessageReceived(contactMessage("poison", future)))
        testScheduler.runCurrent()
        session.emit(MeshEvent.ContactMessageReceived(contactMessage("after", future + 1)))
        testScheduler.runCurrent()

        assertSame(fault, thrown.single())
        assertTrue(backing.savedMessages().isEmpty())
        assertTrue(failures.isEmpty())
        scope.cancel()
    }

    @Test
    fun cliTextTypeBypassesPipeline() = runTest {
        val store = InMemoryIngestStore()
        store.seedContact(TEST_SENDER_PREFIX, resolvedContact())
        val session = FakeIngestSession()
        val cliMessages = mutableListOf<String>()
        session.queue(
            GetMessageResult.ContactMessageReceived(
                contactMessage("cli payload", future, textType = TextType.CLI_DATA.raw),
            ),
        )
        val poller = buildPoller(
            session,
            store,
            backgroundScope,
            buildPipeline(store),
            edges = MessagePollerEdges(
                now = { INGEST_NOW_SECONDS },
                cliMessageHandler = { message, _ -> cliMessages += message.text },
            ),
        )

        val count = poller.pollAllMessages()

        assertEquals(1, count)
        assertTrue(store.savedMessages().isEmpty())
        assertEquals(listOf("cli payload"), cliMessages)
    }

    @Test
    fun liveCliDoesNotInvokeTheBacklogHandler() = runTest {
        val store = InMemoryIngestStore()
        store.seedContact(TEST_SENDER_PREFIX, resolvedContact())
        val session = FakeIngestSession()
        val cliMessages = mutableListOf<String>()
        val poller = buildPoller(
            session,
            store,
            backgroundScope,
            buildPipeline(store),
            edges = MessagePollerEdges(
                now = { INGEST_NOW_SECONDS },
                cliMessageHandler = { message, _ -> cliMessages += message.text },
            ),
        )
        poller.startEventMonitoring()
        testScheduler.runCurrent()

        session.emit(
            MeshEvent.ContactMessageReceived(
                contactMessage("live cli", future, textType = TextType.CLI_DATA.raw),
            ),
        )
        testScheduler.runCurrent()

        assertTrue(cliMessages.isEmpty(), "live CLI is owned by NodeCliCoordinator, not the poller handler")
        assertTrue(store.savedMessages().isEmpty())
    }

    @Test
    fun signedTextTypeBypassesPipeline() = runTest {
        val store = InMemoryIngestStore()
        store.seedContact(TEST_SENDER_PREFIX, resolvedContact())
        val session = FakeIngestSession()
        val signedMessages = mutableListOf<String>()
        session.queue(
            GetMessageResult.ContactMessageReceived(
                contactMessage("signed payload", future, textType = TextType.SIGNED_PLAIN.raw),
            ),
        )
        val poller = buildPoller(
            session,
            store,
            backgroundScope,
            buildPipeline(store),
            edges = MessagePollerEdges(
                now = { INGEST_NOW_SECONDS },
                signedMessageHandler = { message, _ -> signedMessages += message.text },
            ),
        )

        poller.pollAllMessages()

        assertTrue(store.savedMessages().isEmpty())
        assertEquals(listOf("signed payload"), signedMessages)
    }
}

private class IngestProbeError : Error("probe")
