// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/MessageService+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.FirmwareRadio
import com.meshcoreone.android.app.container.openMemoryDatabase
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.services.messaging.ChatSendQueueService
import com.meshcoreone.android.core.services.messaging.MessageService
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import com.meshcoreone.android.core.services.messaging.PoolBackoffConfig
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DirectQueueFailureOriginalPredicateTest {
    @Test fun originalUnstartedSessionRetainsTheCompletePendingRowWhileActuallyParked() = runTest {
        withFixture(connected = false) { f ->
            assertEquals(RadioId(f.device.id), f.radio)
            assertNotEquals(f.device.radioId, f.radio)
            f.startDrain()
            advanceTimeBy(200)
            runCurrent()

            assertEquals(MessageServiceError.NotConnected,
                (f.failure("park") as MessageServiceException).error)
            f.assertParked()
            assertNull(f.session.currentSelfInfo)
            assertFalse(f.transport.isConnected())
            assertTrue(f.transport.sent.isEmpty())
        }
    }

    @Test fun originalTableFullExhaustsTwoPoolRetriesWithoutBroadcastingFailed() = runTest {
        val config = MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.01))
        withFixture(connected = true, config = config, firmwareError = 3) { f ->
            val subscription = f.messages.statusEvents()
            f.startDrain()
            advanceTimeBy(300)
            runCurrent()

            val failure = (f.failure("park") as MessageServiceException).error
            assertTrue(failure is MessageServiceError.SessionError)
            val underlying = (failure as MessageServiceError.SessionError).underlying
            assertTrue(underlying is MeshCoreException.DeviceError)
            assertEquals(3.toUByte(), (underlying as MeshCoreException.DeviceError).code)
            assertEquals(3, f.directSends().size)
            f.assertParked()
            f.messages.finishStatusEvents()
            val events = subscription.events.toList()
            assertTrue(events.all { it.token == f.token })
            assertEquals(0, events.map { it.event }.filterIsInstance<MessageStatusEvent.Failed>().size)
            subscription.close()
        }
    }

    @Test fun originalIllegalArgumentBroadcastsExactlyOneFailureForTheOriginalMessageID() = runTest {
        withFixture(connected = true, firmwareError = 6) { f ->
            val subscription = f.messages.statusEvents()
            f.startDrain()
            f.queue.awaitDrainCompletion()

            val failure = (f.failure("terminal") as MessageServiceException).error
            assertTrue(failure is MessageServiceError.SessionError)
            val underlying = (failure as MessageServiceError.SessionError).underlying
            assertTrue(underlying is MeshCoreException.DeviceError)
            assertEquals(6.toUByte(), (underlying as MeshCoreException.DeviceError).code)
            assertEquals(1, f.directSends().size)
            assertEquals(f.message.copy(status = MessageStatus.FAILED), f.store.fetchMessage(f.key))
            assertTrue(f.store.fetchPendingSends(f.radio).isEmpty())
            assertFalse(f.store.hasPendingSend(f.key))
            assertEquals(0, f.queue.queuedEnvelopeCount)
            f.messages.finishStatusEvents()
            val events = subscription.events.toList()
            assertTrue(events.all { it.token == f.token })
            assertEquals(listOf(f.message.id),
                events.map { it.event }.filterIsInstance<MessageStatusEvent.Failed>().map { it.messageID })
            subscription.close()
        }
    }

    private suspend fun TestScope.withFixture(
        connected: Boolean,
        config: MessageServiceConfig = MessageServiceConfig(),
        firmwareError: Int = 3,
        body: suspend (Fixture) -> Unit,
    ) {
        val f = Fixture(this, connected, config, firmwareError)
        try {
            f.seed()
            body(f)
            assertEquals(listOf(f.device), f.store.fetchDevices())
            assertEquals(listOf(f.contact.copy(lastMessageDate = f.message.createdAt)), f.store.fetchContacts(f.radio))
        } finally {
            try { assertTrue(f.queue.shutdown().isComplete) } finally {
                try { assertTrue(f.messages.close().isComplete) } finally {
                    try { f.session.stop() } finally {
                        try { f.store.close() } finally {
                            try { f.job.cancelAndJoin() } finally { f.database.close() }
                        }
                    }
                }
            }
        }
    }

    private class Fixture(
        val test: TestScope,
        private val connected: Boolean,
        config: MessageServiceConfig,
        firmwareError: Int,
    ) {
        val job = SupervisorJob(test.coroutineContext[Job])
        private val scope = CoroutineScope(test.coroutineContext + job)
        private val instant = Instant.ofEpochSecond(1_704_067_200)
        private val clock = object : SessionClock {
            override val now: Duration get() = test.testScheduler.currentTime.milliseconds
            override val wallClock: Clock = Clock.fixed(instant, ZoneOffset.UTC)
            override suspend fun sleepFor(duration: Duration) = delay(duration)
        }
        val device = DeviceDTO(
            id = UUID.randomUUID(), radioId = RadioId(UUID.randomUUID()),
            publicKey = Bytes(ByteArray(32) { if (connected) 0x22 else 1 }),
            nodeName = if (connected) "Test Radio" else "Test Device",
            firmwareVersion = 0u, firmwareVersionString = "", manufacturerName = "", buildDate = "",
            maxContacts = 100u, maxChannels = 8u, frequency = 915_000u, bandwidth = 250_000u,
            spreadingFactor = 10u, codingRate = 5u, txPower = 20, maxTxPower = 20,
            latitude = 0.0, longitude = 0.0, blePin = 0u, clientRepeat = false, pathHashMode = 0u,
            defaultFloodScopeName = null, preRepeatFrequency = null, preRepeatBandwidth = null,
            preRepeatSpreadingFactor = null, preRepeatCodingRate = null, manualAddContacts = false,
            autoAddConfig = 0u, autoAddMaxHops = 0u, multiAcks = 2u, telemetryModeBase = 2u,
            telemetryModeLoc = 0u, telemetryModeEnv = 0u, advertLocationPolicy = 0u,
            lastConnected = instant, lastContactSync = 0u, isActive = false, ocvPreset = null,
            appliedRadioPresetID = null, customOCVArrayString = null,
            connectionMethods = SnapshotList.empty(), knownRegions = SnapshotList.empty(),
        )
        val radio = if (connected) device.radioId else RadioId(device.id)
        val token = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), radio)
        val contact = ContactDTO(
            id = UUID.randomUUID(), radioId = radio,
            publicKey = Bytes(ByteArray(32) { if (connected) 0x33 else 2 }), name = "Test Contact",
            typeRawValue = 0u, flags = 0u, outPathLength = 255u, outPath = Bytes.EMPTY,
            lastAdvertTimestamp = 0u, latitude = 0.0, longitude = 0.0, lastModified = 0u,
            lastHeardTimestamp = 0u, nickname = null, isBlocked = false, isMuted = false,
            isFavorite = false, lastMessageDate = null, unreadCount = 0, unreadMentionCount = 0,
            ocvPreset = null, customOCVArrayString = null, avatarImageData = null,
        )
        val database = openMemoryDatabase()
        val store = RoomPersistenceStore(database, scope, clock.wallClock)
        val transport = FirmwareRadio(Bytes(ByteArray(32) { 0xAB.toByte() })).also { radio ->
            radio.respond = { command ->
                when (command[0].toInt()) {
                    1 -> listOf(Bytes.fromHex("05001414" + "ab".repeat(32) +
                        "00000000000000000200020038f60d0090d003000a05546573744e6f6465"))
                    2 -> listOf(Bytes.of(1, firmwareError))
                    else -> error("Unexpected command in DM failure original: ${command.hexString}")
                }
            }
        }
        val session = MeshCoreSession(transport,
            SessionConfiguration(defaultTimeout = if (connected) 0.2 else 5.0), clock, scope.coroutineContext)
        private val signals = Signals(token, connected)
        private val diagnostics = mutableListOf<MessagingDiagnostic>()
        private val reporter = MessagingIssueReporter { diagnostics += it }
        val messages = MessageService(token,
            if (connected) Bytes(ByteArray(32) { 0xAB.toByte() }) else device.publicKey,
            session, store, signals, scope, config, clock, jitter = { 1.0 }, reporter = reporter)
        val queue = ChatSendQueueService(token, store, messages,
            MessagingChannelQuery { error("DM originals must not query channels") },
            OutgoingChannelReactionIndexer { _, _, _, _, _ -> error("DM originals must not index channel reactions") },
            signals, scope, clock = clock, reporter = reporter)
        lateinit var message: MessageDTO
        lateinit var pending: PendingSendDTO
        val key get() = EntityKey(radio, message.id)

        suspend fun seed() {
            assertNotEquals(device.id, device.radioId.value)
            store.saveDevice(device)
            store.saveContact(contact)
            assertEquals(device, store.fetchDevice(device.id))
            assertEquals(contact, store.fetchContact(EntityKey(radio, contact.id)))
            if (connected) {
                session.start()
                assertEquals(SelfInfo(0u, 20, 20, Bytes(ByteArray(32) { 0xAB.toByte() }),
                    0.0, 0.0, 2u, 0u, 0u, 0u, 2u, false, 915.0, 250.0, 10u, 5u, "TestNode"),
                    session.currentSelfInfo)
            }
            val created = messages.createPendingMessage("Hello", contact)
            message = MessageDTO(
                id = created.id, radioId = radio, contactID = contact.id, channelIndex = null,
                text = "Hello", timestamp = 1_704_067_200u, createdAt = instant, sortDate = instant,
                direction = MessageDirection.OUTGOING, status = MessageStatus.PENDING, textType = TextType.PLAIN,
                ackCode = null, pathLength = 0u, snr = null, pathNodes = null, senderKeyPrefix = null,
                senderNodeName = null, isRead = false, replyToID = null, roundTripTime = null,
                heardRepeats = 0, sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0,
                deduplicationKey = null, linkPreviewURL = null, linkPreviewTitle = null,
                linkPreviewImageData = null, linkPreviewIconData = null, linkPreviewFetched = false,
                containsSelfMention = false, mentionSeen = false, failureSeen = false,
                timestampCorrected = false, senderTimestamp = null, reactionSummary = null,
                routeType = null, regionScope = null, regionScopeMatches = SnapshotList.empty(),
            )
            assertEquals(message, created)
            assertEquals(message, store.fetchMessage(key))
            val dto = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio,
                enqueuedAt = instant)
            pending = PendingSendDTO(dto.id, radio, message.id, PendingSendKind.DM, contact.id,
                null, false, "", 0u, null, 0, instant, 0)
            assertEquals(pending, dto)
            assertEquals(1L, store.insertPendingSendAssigningSequence(dto))
            pending = pending.copy(sequence = 1)
            assertEquals(listOf(pending), store.fetchPendingSends(radio))
        }

        suspend fun startDrain() {
            queue.hydrate()
            test.runCurrent()
            signals.ready()
            test.runCurrent()
        }

        fun failure(phase: String): Throwable = diagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
            .single { it.operation == "$phase.${message.id}" }.cause

        suspend fun assertParked() {
            assertEquals(listOf(pending.copy(attemptCount = 1)), store.fetchPendingSends(radio))
            assertTrue(store.hasPendingSend(key))
            assertEquals(message, store.fetchMessage(key))
            assertEquals(1, queue.queuedEnvelopeCount)
        }

        fun directSends() = transport.sent.filter { it[0].toInt() == 2 }
    }

    private class Signals(private val token: SessionToken, private val connected: Boolean) : ConnectionSignals {
        private val subscriptions = mutableSetOf<Channel<ConnectionSnapshot>>()
        override val snapshot = MutableStateFlow(value(
            if (connected) DeviceConnectionState.CONNECTED else DeviceConnectionState.DISCONNECTED))
        private fun value(state: DeviceConnectionState) = ConnectionSnapshot(state,
            if (connected) ConnectionState.Connected else ConnectionState.Disconnected,
            null, ConnectionIntent.WantsConnection(), token, null)
        fun ready() {
            snapshot.value = value(DeviceConnectionState.READY)
            subscriptions.forEach { it.trySend(snapshot.value).getOrThrow() }
        }
        override suspend fun subscribeTransitions(): ConnectionSubscription {
            val channel = Channel<ConnectionSnapshot>(Channel.UNLIMITED)
            subscriptions += channel
            return object : ConnectionSubscription {
                override val initial = snapshot.value
                override val transitions = channel.receiveAsFlow()
                override fun close() { subscriptions -= channel; channel.close() }
            }
        }
    }
}
