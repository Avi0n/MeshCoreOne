// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Adaptation: WP-501 Actual App/Room graph over framed ERROR2; ungated Bluetooth recovery and real monotonic deadlines.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.services.contacts.ChannelServiceError
import com.meshcoreone.android.core.services.messaging.ChatSendQueueConfig
import com.meshcoreone.android.core.services.messaging.ChatSendQueueProgress
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import com.meshcoreone.android.core.services.messaging.PoolBackoffConfig
import com.meshcoreone.android.core.services.rendering.DraftStore
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.Test

class ChannelQueueCounterAppOriginalPredicateTest : RoomProcessTest() {
    @OriginalCase("ChatSendQueueServiceTests::Channel fetchChannel failure counter is per-envelope and does not cascade")
    @Test fun originalAReachesTerminalAndBRetainsItsRowForLiteralThreeSecondsThroughTheUngatedApp() = runBlocking {
        val radio = RadioId(UUID.fromString("a0000000-0000-0000-0000-000000000011"))
        val device = UUID.fromString("a0000000-0000-0000-0000-000000000012")
        val a = MessageDTO(
            id = UUID.fromString("a0000000-0000-0000-0000-000000000013"),
            radioId = radio, channelIndex = 0u, text = "Hello channel", timestamp = 1_704_067_200u,
            createdAt = EPOCH, sortDate = EPOCH, direction = MessageDirection.OUTGOING,
            status = MessageStatus.PENDING,
        )
        val b = a.copy(id = UUID.fromString("a0000000-0000-0000-0000-000000000014"), text = "Second message")
        val pendingA = PendingSendDTO(
            UUID.fromString("a0000000-0000-0000-0000-000000000015"), radio, a.id, PendingSendKind.CHANNEL,
            null, 0u, true, a.text, a.timestamp, "Test Radio", 1, EPOCH, 7,
        )
        val pendingB = PendingSendDTO(
            UUID.fromString("a0000000-0000-0000-0000-000000000016"), radio, b.id, PendingSendKind.CHANNEL,
            null, 0u, false, b.text, b.timestamp, "Test Radio", 2, EPOCH, 2,
        )
        store.saveDevice(DeviceDTO(id = device, radioId = radio, publicKey = key(7),
            nodeName = "Test Radio", isActive = false))
        store.saveChannel(ChannelDTO(
            UUID.fromString("a0000000-0000-0000-0000-000000000017"), radio, 0u, "Test Channel",
            Bytes.fromHex("aa".repeat(16)),
        ))
        store.saveMessage(a)
        store.saveMessage(b)
        store.upsertPendingSend(pendingA)
        store.upsertPendingSend(pendingB)
        assertEquals(listOf(pendingA, pendingB), store.fetchPendingSends(radio))

        val diagnostics = Collections.synchronizedList(mutableListOf<MessagingDiagnostic>())
        val queries = Collections.synchronizedList(mutableListOf<Bytes>())
        val links = HarnessLinks { key(7) }
        links.configureRadio = { firmware ->
            val sending = AtomicBoolean()
            firmware.respond = { command ->
                when (command[0].toInt()) {
                    3 -> { sending.set(true); listOf(Bytes.of(1, 2)) }
                    31 -> if (sending.get()) {
                        queries += command
                        listOf(Bytes.of(1, 2))
                    } else {
                        listOf(if (command[1] == 0.toUByte())
                            Bytes.of(0x12, 0) + Bytes.utf8("Test Channel").paddedOrTruncated(32) +
                                Bytes.fromHex("aa".repeat(16))
                        else Bytes.of(0x12, command[1].toInt()) + Bytes(ByteArray(48)))
                    }
                    else -> null
                }
            }
        }
        val uiJob = SupervisorJob(coroutineContext[Job])
        val container = AppContainer(AppContainerDependencies(
            store = store,
            passwords = MemoryVault(),
            connectionPreferences = MemoryConnectionPreferences(),
            connectivity = newConnectivity(HarnessPairingService()) {},
            linkProbe = NoSystemLinks,
            scans = newScans(),
            linkFactory = links,
            notificationDelivery = RecordingDelivery(),
            notificationPreferences = FixedNotificationPreferences(),
            contactPreferences = MemoryFlags(),
            draftStore = DraftStore(MemoryDraftDefaults()),
            mainScope = CoroutineScope(Dispatchers.Default + uiJob),
            chatSendQueueProgress = ChatSendQueueProgress(),
            messagingReporter = MessagingIssueReporter { diagnostics += it },
            runtimeSessionConfiguration = SessionConfiguration(defaultTimeout = 0.2),
            messageServiceConfig = MessageServiceConfig(
                poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.01),
            ),
            chatSendQueueConfig = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 2),
        ))
        var trigger: Job? = null
        var primary: Throwable? = null
        fun failures(phase: String, id: UUID): List<Throwable> = synchronized(diagnostics) {
            diagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
                .filter { it.operation == "$phase.$id" }.map { it.cause }
        }
        try {
            val target = ConnectionTarget.Bluetooth(
                BluetoothPairingHandle(BluetoothAddress(syntheticAddress(device)), null), device,
            )
            withTimeout(15_000) { container.connectionManager.connect(target) }
            val live = assertNotNull(container.sessions.current)
            assertSame(store, live.dataStore)
            assertEquals(radio, live.token.radioId)
            assertEquals(2L, live.chatSendQueueService.config.maxConsecutiveFetchChannelFailures)
            assertEquals(PoolBackoffConfig(attemptCap = 2, baseDelay = 0.01),
                live.messageService.config.poolBackoff)
            live.chatSendQueueService.hydrate()
            val aDeadline = System.nanoTime() + 30_000_000_000L
            trigger = launch(Dispatchers.Default) {
                while (isActive) {
                    container.sessions.current?.chatSendQueueService?.transportDidOpen()
                    delay(50)
                }
            }
            while (store.fetchPendingSends(radio).any { it.messageID == a.id } &&
                System.nanoTime() < aDeadline) {
                delay(50)
            }
            assertTrue(store.fetchPendingSends(radio).none { it.messageID == a.id },
                "A must be deleted within the original real 30-second deadline; " +
                    "state=${container.connectionManager.connectionState}, " +
                    "queryFailures=${failures("channelQuery", a.id)}, rows=${store.fetchPendingSends(radio)}")
            assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radio, a.id))?.status)
            val aFailures = failures("channelQuery", a.id)
            assertEquals(2, aFailures.size, "A must exhaust two genuine channel queries, not fail on cancellation")
            aFailures.forEach { failure ->
                val query = assertIs<ChannelServiceError.SessionError>(failure)
                assertChannelQueryUnresolved(query.error, "channel:0")
            }
            assertTrue(synchronized(queries) { queries.all { it == Bytes.of(31, 0) } })

            val bStart = System.nanoTime()
            delay(3_000)
            assertTrue(System.nanoTime() - bStart >= 3_000_000_000L)
            assertTrue(store.fetchPendingSends(radio).any { it.messageID == b.id },
                "B's row must survive the literal three-second window; status=" +
                    store.fetchMessage(EntityKey(radio, b.id))?.status)
            assertTrue(failures("channelQuery", b.id).isNotEmpty(),
                "B must reach a real disambiguation failure; absence of a connection is not counter isolation")
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                trigger?.cancelAndJoin()
                try { container.close() }
                catch (cleanup: Throwable) {
                    val failure = primary
                    if (failure == null) throw cleanup
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                } finally { uiJob.cancelAndJoin() }
            }
        }
    }
}
