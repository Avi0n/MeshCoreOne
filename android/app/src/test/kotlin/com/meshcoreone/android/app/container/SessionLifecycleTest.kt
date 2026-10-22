// AndroidOnly: WP-303 Native proof: reconnect and radio switch build one graph per generation and release every monitor and stream.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.MessagingDiagnostic
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.PendingSendDTO
import com.meshcoreone.android.core.model.PendingSendKind
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.session.SessionCorrelationException
import com.meshcoreone.android.core.runtime.ConnectionRetryPolicy
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.runtime.RuntimeDiagnostic
import com.meshcoreone.android.core.runtime.SystemRuntimeClock
import com.meshcoreone.android.core.services.diagnostics.DebugLogBuffer
import com.meshcoreone.android.core.services.contacts.ChannelServiceError
import com.meshcoreone.android.core.services.messaging.ChatSendQueueConfig
import com.meshcoreone.android.core.services.messaging.ChatSendQueueService
import com.meshcoreone.android.core.services.messaging.ChannelQueryAccountingEvent
import com.meshcoreone.android.core.services.messaging.ChannelQueryAccountingOutcome
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import com.meshcoreone.android.core.services.messaging.PoolBackoffConfig
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifecycleTest : RoomProcessTest() {
    private suspend fun ContainerHarness.connect(target: ConnectionTarget = target()) {
        manager.connect(target)
        settle()
        assertReady()
    }

    private suspend fun ContainerHarness.disconnect() {
        manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
        settle()
    }

    @Test
    fun reconnectCyclesKeepOneLiveGraphAndNeverGrowSubscribersOrJobs() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val seen = mutableListOf<RadioSessionContainer>()
            val dispatcherJobs = mutableListOf<Int>()
            val sessionJobs = mutableListOf<Int>()
            val dataSubscribers = mutableListOf<Int>()
            repeat(3) {
                h.connect()
                val live = assertNotNull(h.sessions.current)
                assertSame(h.chatSendQueueProgress, live.chatSendQueueProgress,
                    "every replacement graph receives the process-owned send progress")
                seen += live
                dispatcherJobs += h.appState.messageEventDispatcher.activeJobCount
                sessionJobs += h.appState.activeSessionJobCount
                dataSubscribers += live.syncCoordinator.dataEventBroadcaster.subscriberCount
                assertEquals(1, h.sessions.outstanding, "exactly one live graph while connected")
                h.disconnect()
                assertNull(h.sessions.current)
                assertEquals(0, h.sessions.outstanding, "no graph survives a disconnect")
            }
            assertEquals(3, h.sessions.created)
            assertEquals(3, h.sessions.tornDown)
            assertEquals(3, seen.map { System.identityHashCode(it) }.toSet().size)
            assertEquals(1, dispatcherJobs.toSet().size, "dispatcher collectors do not accumulate: $dispatcherJobs")
            assertEquals(1, sessionJobs.toSet().size, "app-state collectors do not accumulate: $sessionJobs")
            assertEquals(1, dataSubscribers.toSet().size, "data-event subscribers do not accumulate: $dataSubscribers")
            for (old in seen) {
                assertTrue(old.isTornDown)
                assertFalse(old.isEventMonitoringActive)
                assertEquals(0, old.liveJobCount, "no coroutine outlives its graph")
                assertEquals(0, old.syncCoordinator.dataEventBroadcaster.subscriberCount)
            }
            assertTrue(h.links.radios.all { it.closes == 1 && it.readers == 0 }, "each physical link closed exactly once")
        } finally { h.container.close() }
    }

    @Test
    fun radioSwitchTearsDownTheOldGraphBeforeTheNewOneServes() = runTest {
        val h = ContainerHarness(this, store, identity = { target ->
            if (target is ConnectionTarget.Bluetooth && target.deviceId == SECOND) key(9) else key(7)
        })
        try {
            h.connect(h.target(FIRST))
            val first = assertNotNull(h.sessions.current)
            val firstRadio = first.token.radioId
            val before = h.appState.servicesVersion.value
            h.manager.connect(h.target(SECOND))
            h.settle()
            h.assertReady()
            val second = assertNotNull(h.sessions.current)
            assertNotSame(first, second)
            assertNotEquals(firstRadio, second.token.radioId, "the second radio is a different identity")
            assertSame(h.chatSendQueueProgress, first.chatSendQueueProgress)
            assertSame(first.chatSendQueueProgress, second.chatSendQueueProgress,
                "radio isolation is keyed within one process-owned progress tracker")
            assertTrue(first.isTornDown)
            assertEquals(0, first.liveJobCount)
            assertEquals(1, h.sessions.outstanding)
            assertTrue(h.appState.servicesVersion.value > before, "views reload on a services change")
            assertSame(second.syncCoordinator, h.appState.syncCoordinator)
            assertEquals(second.token.radioId, h.appState.currentRadioId)
        } finally { h.container.close() }
    }

    @Test
    fun foregroundAppReconnectsAutomaticallyAndIsolatesEachPendingEnvelopeChannelQueryFailure() = runTest(
        timeout = NATIVE_QUERY_RECOVERY_WINDOW + 3.seconds + 30.seconds,
    ) {
        val radioId = RadioId(UUID.fromString("A0000000-0000-0000-0000-000000000001"))
        val deviceId = UUID.fromString("A0000000-0000-0000-0000-000000000002")
        val messageAId = UUID.fromString("A0000000-0000-0000-0000-000000000004")
        val pendingAId = UUID.fromString("A0000000-0000-0000-0000-000000000005")
        val messageBId = UUID.fromString("A0000000-0000-0000-0000-000000000006")
        val pendingBId = UUID.fromString("A0000000-0000-0000-0000-000000000007")
        val accountingReceipts = ConcurrentLinkedQueue<Pair<ChannelQueryAccountingEvent, SessionToken?>>()
        lateinit var h: ContainerHarness
        h = ContainerHarness(
            this,
            store,
            runtimeClock = SystemRuntimeClock(),
            runtimeContext = Dispatchers.Default,
            sessionConfiguration = SessionConfiguration(defaultTimeout = 0.2),
            messageServiceConfig = MessageServiceConfig(
                poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.01),
            ),
            chatSendQueueConfig = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = QUERY_FAILURE_CAP),
            mainScope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Default.limitedParallelism(1)),
            onChannelQueryAccounting = { event ->
                accountingReceipts += event to h.manager.snapshot.value.token
            },
        )
        val activeRadio = AtomicReference<FirmwareRadio?>()
        val bootstrapChannelRequests = ConcurrentLinkedQueue<Pair<SessionToken, UByte>>()
        val queueChannelRequests = ConcurrentLinkedQueue<Pair<SessionToken, UByte>>()
        h.links.configureRadio = { radio ->
            activeRadio.set(radio)
            val sending = AtomicBoolean(false)
            radio.respond = { command ->
                when (command[0].toInt()) {
                    3 -> {
                        check(h.manager.connectionState == DeviceConnectionState.READY)
                        sending.set(true)
                        emptyList()
                    }
                    31 -> {
                        val index = command[1]
                        val token = checkNotNull(h.manager.snapshot.value.token)
                        if (sending.get() && h.manager.connectionState == DeviceConnectionState.READY) {
                            check(index == 0.toUByte())
                            queueChannelRequests += token to index
                            emptyList()
                        } else {
                            bootstrapChannelRequests += token to index
                            listOf(
                                Bytes.of(0x12, index.toInt()) +
                                    Bytes.utf8(if (index == 0.toUByte()) "Test" else "").paddedOrTruncated(32) +
                                    Bytes(ByteArray(16) { if (index == 0.toUByte()) 0xaa.toByte() else 0 }),
                            )
                        }
                    }
                    else -> null
                }
            }
        }

        val channel = ChannelDTO(
            id = UUID.fromString("A0000000-0000-0000-0000-000000000003"),
            radioId = radioId,
            index = 0u,
            name = "Test",
            secret = Bytes.fromHex("aa".repeat(16)),
        )
        val messageA = MessageDTO(
            id = messageAId,
            radioId = radioId,
            channelIndex = 0u,
            text = "Hello channel",
            timestamp = 1_704_067_200u,
            createdAt = EPOCH,
            sortDate = EPOCH,
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.PENDING,
        )
        val messageB = messageA.copy(
            id = messageBId,
            text = "Second message",
        )
        store.saveDevice(DeviceDTO(
            id = deviceId,
            radioId = radioId,
            publicKey = key(7),
            nodeName = "Stored Node",
            isActive = false,
        ))
        store.saveChannel(channel)
        store.saveMessage(messageA)
        store.saveMessage(messageB)
        // A's first bump reaches 8, above the disambiguation threshold; B's reaches 3, exactly at it.
        store.upsertPendingSend(PendingSendDTO(
            id = pendingAId,
            radioId = radioId,
            messageID = messageAId,
            kind = PendingSendKind.CHANNEL,
            contactID = null,
            channelIndex = 0u,
            isResend = true,
            messageText = messageA.text,
            messageTimestamp = messageA.timestamp,
            localNodeName = "Stored Node",
            sequence = 1,
            enqueuedAt = EPOCH,
            attemptCount = 7,
        ))
        store.upsertPendingSend(PendingSendDTO(
            id = pendingBId,
            radioId = radioId,
            messageID = messageBId,
            kind = PendingSendKind.CHANNEL,
            contactID = null,
            channelIndex = 0u,
            isResend = false,
            messageText = messageB.text,
            messageTimestamp = messageB.timestamp,
            localNodeName = "Stored Node",
            sequence = 2,
            enqueuedAt = EPOCH,
            attemptCount = 2,
        ))

        var errorDispatcher: Job? = null
        var transportOpenTrigger: Job? = null
        var queue: ChatSendQueueService? = null
        fun failures(phase: String, messageID: UUID): List<MessagingDiagnostic.Failure> =
            synchronized(h.messagingDiagnostics) {
                h.messagingDiagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
                    .filter { it.token.radioId == radioId && it.operation == "$phase.$messageID" }
            }

        fun receipts(messageID: UUID): List<Pair<ChannelQueryAccountingEvent, SessionToken?>> =
            accountingReceipts.filter { it.first.token.radioId == radioId && it.first.messageID == messageID }

        fun assertPersistenceReceipt(
            observed: Pair<ChannelQueryAccountingEvent, SessionToken?>,
            query: MessagingDiagnostic.Failure,
            messageID: UUID,
            pendingID: UUID,
            count: Long,
            outcome: ChannelQueryAccountingOutcome,
        ) {
            val (receipt, tokenAtArrival) = observed
            assertEquals(query.token, receipt.token)
            assertEquals(receipt.token, tokenAtArrival, "Persistence receipt must arrive while its runtime token is current")
            assertEquals(messageID, receipt.messageID)
            assertEquals(pendingID, receipt.pendingID)
            assertEquals(count, receipt.failureCount)
            assertEquals(QUERY_FAILURE_CAP, receipt.failureCap)
            assertEquals(outcome, receipt.outcome)
            assertSame(query.cause, receipt.queryFailure)
        }

        fun assertNoAccountingOrTeardownFailure(description: String) {
            val runtime = synchronized(h.diagnostics) { h.diagnostics.toList() }
            val fallbacks = runtime.filterIsInstance<RuntimeDiagnostic.Failure>().filter {
                it.operation == "session.channelQueryAccounting.cancelled" ||
                    it.operation == "session.channelQueryAccounting"
            }
            assertTrue(fallbacks.isEmpty(), "$description must not use accounting fallback: $fallbacks")
            val teardown = runtime.filterIsInstance<RuntimeDiagnostic.Teardown>().filter { !it.report.isComplete }
            assertTrue(teardown.isEmpty(), "$description must finish without teardown issues: $teardown")
            val storage = synchronized(h.messagingDiagnostics) {
                h.messagingDiagnostics.filterIsInstance<MessagingDiagnostic.Failure>().filter {
                    it.operation.startsWith("remap.") || it.operation.startsWith("terminalPersistence.")
                }
            }
            assertTrue(storage.isEmpty(), "$description must not mask failed persistence: $storage")
        }

        fun assertGenuineQueryFailure(failure: MessagingDiagnostic.Failure) {
            val wrapped = assertIs<ChannelServiceError.SessionError>(failure.cause)
            assertIs<MeshCoreException.ConnectionLost>(wrapped.error)
            val chain = generateSequence<Throwable>(wrapped.error) { it.cause }.take(16).toList()
            val unresolved = assertIs<SessionCorrelationException.UnresolvedReply>(
                chain.dropWhile { it is MeshCoreException.ConnectionLost }.firstOrNull(),
            )
            assertEquals(1L, unresolved.generation, "Each physical generation constructs a fresh protocol session")
            assertEquals("channel:0", unresolved.responseFamily)
            assertTrue(queueChannelRequests.any { it.first == failure.token && it.second == 0.toUByte() },
                "A typed failure must originate from an actual queue GET_CHANNEL in that generation")
        }

        try {
            assertTrue(h.container.foreground.activityStarted())
            h.appState.handleBecameActive()
            h.appState.handleReturnToForeground()
            errorDispatcher = backgroundScope.launch(Dispatchers.Default) {
                while (isActive) {
                    if (h.manager.connectionState == DeviceConnectionState.READY) {
                        activeRadio.get()?.push(Bytes.of(1, 2))
                    }
                    delay(20.milliseconds)
                }
            }
            transportOpenTrigger = backgroundScope.launch(Dispatchers.Default) {
                while (isActive) {
                    h.sessions.current?.chatSendQueueService?.transportDidOpen()
                    delay(50.milliseconds)
                }
            }

            val started = System.nanoTime()
            suspend fun awaitNativeRecovery(description: String, predicate: suspend () -> Boolean) {
                while (System.nanoTime() - started < NATIVE_QUERY_RECOVERY_WINDOW.inWholeNanoseconds) {
                    if (predicate()) return
                    withContext(Dispatchers.Default) { delay(50.milliseconds) }
                }
                throw AssertionError("$description within $NATIVE_QUERY_RECOVERY_WINDOW; " +
                    "A queries=${failures("channelQuery", messageAId)}, B queries=${failures("channelQuery", messageBId)}, " +
                    "receipts=${accountingReceipts.toList()}")
            }
            suspend fun awaitGenerationRetired(token: SessionToken, description: String) {
                awaitNativeRecovery(description) {
                    h.sessions.tornDown > 0 &&
                        h.sessions.current?.token != token &&
                        h.manager.connectionState == DeviceConnectionState.DISCONNECTED &&
                        h.manager.isReconnectionWatchdogRunning
                }
            }

            h.container.uiScope.async { h.manager.connect(h.target(deviceId)) }.await()
            h.assertReady()
            val live = assertNotNull(h.sessions.current)
            assertEquals(radioId, live.token.radioId)
            queue = live.chatSendQueueService
            assertEquals((0 until 8).map { it.toUByte() }.toSet(),
                bootstrapChannelRequests.filter { it.first == live.token }.map { it.second }.toSet(),
                "Initial foreground sync must read every real advertised channel slot with matching-index replies")

            awaitNativeRecovery("Envelope A's first real channel-query failure must persist its park") {
                failures("channelQuery", messageAId).isNotEmpty() &&
                    failures("park", messageAId).isNotEmpty() && receipts(messageAId).isNotEmpty()
            }
            val firstAQuery = failures("channelQuery", messageAId).single()
            assertGenuineQueryFailure(firstAQuery)
            assertSame(firstAQuery.cause, failures("park", messageAId).single().cause)
            assertPersistenceReceipt(receipts(messageAId).single(), firstAQuery, messageAId, pendingAId,
                1L, ChannelQueryAccountingOutcome.PARKED)
            assertEquals(
                pendingAId,
                store.fetchPendingSends(radioId).firstOrNull { it.messageID == messageAId }?.id,
                "A's first query failure must retain its original durable pending identity",
            )
            awaitGenerationRetired(firstAQuery.token, "A's first quarantined generation must finish accounting before retirement")
            assertNoAccountingOrTeardownFailure("A's first retired generation")

            awaitNativeRecovery("Envelope A must genuinely exhaust its query cap, be deleted and become FAILED") {
                val pending = store.fetchPendingSends(radioId).any { it.messageID == messageAId }
                val status = store.fetchMessage(EntityKey(radioId, messageAId))?.status
                !pending && status == MessageStatus.FAILED && failures("terminal", messageAId).isNotEmpty() &&
                    receipts(messageAId).size >= QUERY_FAILURE_CAP.toInt()
            }
            val aQueries = failures("channelQuery", messageAId)
            assertEquals(QUERY_FAILURE_CAP.toInt(), aQueries.size, "Only genuine query failures may exhaust A's cap")
            aQueries.forEach(::assertGenuineQueryFailure)
            assertEquals(QUERY_FAILURE_CAP.toInt(), aQueries.map { it.token }.distinct().size,
                "A must cross real physical generation retirement and automatic reconnect")
            assertSame(aQueries.last().cause, failures("terminal", messageAId).single().cause)
            val aReceipts = receipts(messageAId)
            assertEquals(listOf(1L, 2L), aReceipts.map { it.first.failureCount })
            assertPersistenceReceipt(aReceipts[0], aQueries[0], messageAId, pendingAId,
                1L, ChannelQueryAccountingOutcome.PARKED)
            assertPersistenceReceipt(aReceipts[1], aQueries[1], messageAId, pendingAId,
                2L, ChannelQueryAccountingOutcome.TERMINAL_DELETED)
            awaitGenerationRetired(aQueries.last().token, "A's terminal query accounting must finish before its generation retires")
            assertNoAccountingOrTeardownFailure("A's terminal retired generation")
            assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radioId, messageAId))?.status)
            assertFalse(store.fetchPendingSends(radioId).any { it.messageID == messageAId })

            awaitNativeRecovery("Envelope B must reach its own genuine query failure and persisted park") {
                failures("channelQuery", messageBId).isNotEmpty() &&
                    failures("park", messageBId).isNotEmpty() && receipts(messageBId).isNotEmpty()
            }
            val bQuery = failures("channelQuery", messageBId).first()
            assertGenuineQueryFailure(bQuery)
            assertSame(bQuery.cause, failures("park", messageBId).first().cause)
            assertPersistenceReceipt(receipts(messageBId).first(), bQuery, messageBId, pendingBId,
                1L, ChannelQueryAccountingOutcome.PARKED)
            assertTrue(bQuery.token.generation.value > aQueries.last().token.generation.value,
                "B cannot be credited while A's quarantined generation is disconnected")
            assertTrue(h.container.foreground.isForeground)
            val foregroundCalls = synchronized(h.foregroundCalls) { h.foregroundCalls.toList() }
            assertTrue(foregroundCalls.isNotEmpty() && foregroundCalls.all { it },
                "Initial foreground and its ordinary scheduled reconciliation remain enabled without background stimuli")
            awaitGenerationRetired(bQuery.token, "B's first query must finish park accounting before physical retirement")
            assertNoAccountingOrTeardownFailure("B's first retired generation")
            val parkedB = assertNotNull(store.fetchPendingSends(radioId).firstOrNull { it.messageID == messageBId })
            assertEquals(pendingBId, parkedB.id)

            withContext(Dispatchers.Default) { delay(3.seconds) }
            val statusB = store.fetchMessage(EntityKey(radioId, messageBId))?.status
            val pendingB = store.fetchPendingSends(radioId).firstOrNull { it.messageID == messageBId }
            assertNotNull(
                pendingB,
                "Envelope B's same-radio PendingSend row must survive the literal three-second observation; status=$statusB",
            )
            assertEquals(pendingBId, pendingB.id)
            assertEquals(radioId, pendingB.radioId)
            assertEquals(messageBId, pendingB.messageID)
        } finally {
            errorDispatcher?.cancel()
            transportOpenTrigger?.cancel()
            withContext(NonCancellable) {
                val queueReport = try { queue?.shutdown() } finally { h.container.close() }
                assertTrue(queueReport?.isComplete != false, "Queue cleanup must not hide teardown issues: $queueReport")
                assertNoAccountingOrTeardownFailure("Final cleanup")
            }
        }
    }

    @Test
    fun teardownFinishesEveryEventStreamAndClearsNotificationForwarders() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val live = assertNotNull(h.sessions.current)
            var completed = false
            // Subscribed straight on the coordinator and collected outside app state: only the container's own
            // finishDataEvents() can end this stream.
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                live.syncCoordinator.dataEvents().collect { }
                completed = true
            }
            assertNotNull(live.notificationService.onQuickReply, "app state installed the forwarders")
            h.disconnect()
            h.eventually("the stream to finish") { completed }
            collector.cancel()
            assertNull(live.notificationService.onQuickReply)
            assertNull(live.notificationService.onChannelQuickReply)
            assertNull(live.notificationService.onMarkAsRead)
            assertNull(live.notificationService.onChannelMarkAsRead)
            assertNull(live.notificationService.onRoomMarkAsRead)
        } finally { h.container.close() }
    }

    @Test
    fun startingMonitorsTwiceDoesNotDuplicateAnyMonitor() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val live = assertNotNull(h.sessions.current)
            assertTrue(live.isEventMonitoringActive)
            val before = live.liveJobCount
            live.startEventMonitoring(live.token.radioId)
            live.startEventMonitoring(live.token.radioId, enableAutoFetch = true, enableAdvertisementMonitoring = true)
            h.settle()
            assertEquals(before, live.liveJobCount, "a second start must be a no-op")
        } finally { h.container.close() }
    }

    @Test
    fun tearDownIsIdempotentAndRefusesToRestartMonitors() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val live = assertNotNull(h.sessions.current)
            h.disconnect()
            val again = live.tearDown()
            assertTrue(again.isComplete, "a repeated teardown replays the first receipt: ${again.issues}")
            live.startEventMonitoring(live.token.radioId)
            assertFalse(live.isEventMonitoringActive, "a torn-down graph never restarts a monitor")
            assertEquals(0, live.liveJobCount)
        } finally { h.container.close() }
    }

    @Test
    fun processGlobalDebugLogBufferNeverPointsAtATornDownGraph() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val live = assertNotNull(h.sessions.current)
            assertSame(live.debugLogBuffer, DebugLogBuffer.shared)
            h.disconnect()
            assertNotSame(live.debugLogBuffer, DebugLogBuffer.shared)
            assertSame(h.container.bootstrapDebugLog, DebugLogBuffer.shared)
        } finally { h.container.close() }
    }

    @Test
    fun explicitDisconnectRunsTheSameSessionTeardownAsConnectionLoss() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            assertTrue(h.appState.activeSessionJobCount > 0)
            h.appState.disconnect()
            h.settle()
            assertEquals(0, h.appState.activeSessionJobCount)
            assertEquals(0, h.appState.messageEventDispatcher.activeJobCount)
            assertEquals(DeviceConnectionState.DISCONNECTED, h.manager.connectionState)
        } finally { h.container.close() }
    }

    private companion object {
        const val QUERY_FAILURE_CAP = 2L
        // Conservative budget for two watchdog delays; ordinary foreground reconciliation may reconnect sooner.
        val NATIVE_QUERY_RECOVERY_WINDOW =
            ConnectionRetryPolicy.watchdogDelay(1) * QUERY_FAILURE_CAP.toInt() + 30.seconds
        val FIRST: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val SECOND: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
    }
}
