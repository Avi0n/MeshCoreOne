// AndroidOnly: WP-303 Native proof: reconnect and radio switch build one graph per generation and release every monitor and stream.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
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
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.runtime.SystemRuntimeClock
import com.meshcoreone.android.core.services.diagnostics.DebugLogBuffer
import com.meshcoreone.android.core.services.messaging.ChatSendQueueConfig
import com.meshcoreone.android.core.services.messaging.ChatSendQueueService
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import com.meshcoreone.android.core.services.messaging.PoolBackoffConfig
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Test

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
    fun sameRadioChannelQueryFailureIsIsolatedToEachPendingEnvelopeInTheAppRoomGraph() = runTest {
        val radioId = RadioId(UUID.fromString("A0000000-0000-0000-0000-000000000001"))
        val deviceId = UUID.fromString("A0000000-0000-0000-0000-000000000002")
        val messageAId = UUID.fromString("A0000000-0000-0000-0000-000000000004")
        val pendingAId = UUID.fromString("A0000000-0000-0000-0000-000000000005")
        val messageBId = UUID.fromString("A0000000-0000-0000-0000-000000000006")
        val pendingBId = UUID.fromString("A0000000-0000-0000-0000-000000000007")
        val h = ContainerHarness(
            this,
            store,
            runtimeClock = SystemRuntimeClock(),
            runtimeContext = Dispatchers.Default,
            sessionConfiguration = SessionConfiguration(defaultTimeout = 0.2),
            messageServiceConfig = MessageServiceConfig(
                poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.01),
            ),
            chatSendQueueConfig = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 2),
        )
        val activeRadio = AtomicReference<FirmwareRadio?>()
        val channelReply = Bytes.fromHex("120054657374" + "00".repeat(28) + "aa".repeat(16))
        h.links.configureRadio = { radio ->
            activeRadio.set(radio)
            val sending = AtomicBoolean(false)
            radio.respond = { command ->
                when (command[0].toInt()) {
                    3 -> {
                        sending.set(true)
                        emptyList()
                    }
                    31 -> if (sending.get()) emptyList() else listOf(channelReply)
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
        try {
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

            val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
            h.container.uiScope.async { h.manager.connect(h.target(deviceId)) }.await()
            h.assertReady()
            val live = assertNotNull(h.sessions.current)
            assertEquals(radioId, live.token.radioId)
            queue = live.chatSendQueueService

            var aTerminalAndDeleted = false
            while (System.nanoTime() < deadline) {
                val pending = store.fetchPendingSends(radioId).any { it.messageID == messageAId }
                val status = store.fetchMessage(EntityKey(radioId, messageAId))?.status
                if (!pending && status == MessageStatus.FAILED) {
                    aTerminalAndDeleted = true
                    break
                }
                withContext(Dispatchers.Default) { delay(50.milliseconds) }
            }
            assertTrue(aTerminalAndDeleted, "Envelope A must be deleted and FAILED within the real 30-second deadline")
            assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radioId, messageAId))?.status)

            withContext(Dispatchers.Default) { delay(3.seconds) }
            val statusB = store.fetchMessage(EntityKey(radioId, messageBId))?.status
            val pendingB = store.fetchPendingSends(radioId).firstOrNull { it.messageID == messageBId }
            assertNotNull(
                pendingB,
                "Envelope B's same-radio PendingSend row must survive the literal three-second observation; status=$statusB",
            )
            assertEquals(radioId, pendingB.radioId)
            assertEquals(messageBId, pendingB.messageID)
        } finally {
            errorDispatcher?.cancel()
            transportOpenTrigger?.cancel()
            withContext(NonCancellable) {
                queue?.shutdown()
                h.container.close()
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
        val FIRST: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val SECOND: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
    }
}
