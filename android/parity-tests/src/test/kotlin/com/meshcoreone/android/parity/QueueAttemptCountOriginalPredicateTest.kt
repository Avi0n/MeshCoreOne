// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceAttemptCountTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import android.database.sqlite.SQLiteConstraintException
import com.meshcoreone.android.app.container.FirmwareRadio
import com.meshcoreone.android.app.container.openMemoryDatabase
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.services.messaging.ChatSendQueueService
import com.meshcoreone.android.core.services.messaging.MessageService
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
class QueueAttemptCountOriginalPredicateTest {
    @Test fun originalFreshRowCommitsAttemptOneAndReplacesTheOneHourOldTimestamp() = runTest {
        withFixture(0) { f ->
            f.startDrain()
            f.awaitAttempt(1, requireFreshTimestamp = true)

            assertEquals(3_600u, 1_704_067_200u - f.message.timestamp)
            f.assertParked(f.pending.copy(attemptCount = 1), f.message.copy(timestamp = 1_704_067_200u))
            assertEquals(MessageServiceError.NotConnected, (f.failure() as MessageServiceException).error)
        }
    }

    @Test fun originalPriorAttemptRowCommitsAttemptTwoAndPreservesTheOneHourOldTimestamp() = runTest {
        withFixture(1) { f ->
            f.startDrain()
            f.awaitAttempt(2, requireFreshTimestamp = false)

            f.assertParked(f.pending.copy(attemptCount = 2), f.message)
            assertEquals(1_704_063_600u, f.message.timestamp)
            assertEquals(MessageServiceError.NotConnected, (f.failure() as MessageServiceException).error)
        }
    }

    @Test fun originalRealBumpFailurePreservesTheCompleteRowTimestampAndPendingBubble() = runTest {
        withFixture(0, expectBumpIssue = true) { f ->
            f.database.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER wp501_attempt_bump_failure BEFORE UPDATE OF attemptCount ON pending_sends
                WHEN OLD.attemptCount = 0 AND NEW.attemptCount = 1
                BEGIN SELECT RAISE(FAIL, 'wp501_attempt_bump'); END
                """.trimIndent(),
            )
            f.startDrain()
            advanceTimeBy(500)
            runCurrent()

            f.assertParked(f.pending, f.message)
            val failure = f.failure()
            assertTrue(failure is PersistenceStoreException)
            val storage = failure as PersistenceStoreException
            assertEquals(PersistenceStoreError.SaveFailed("incrementPendingSendAttemptCount"), storage.error)
            assertTrue(storage.cause is SQLiteConstraintException)
            assertTrue(requireNotNull(storage.cause?.message).contains("wp501_attempt_bump"))
        }
    }

    private suspend fun TestScope.withFixture(
        attempts: Long,
        expectBumpIssue: Boolean = false,
        body: suspend (Fixture) -> Unit,
    ) {
        val f = Fixture(this, attempts)
        try {
            f.seed()
            body(f)
            assertEquals(listOf(f.device), f.store.fetchDevices())
            assertEquals(listOf(f.contact.copy(lastMessageDate = f.message.createdAt)), f.store.fetchContacts(f.radio))
            assertTrue(f.store.fetchChannels(f.radio).isEmpty())
        } finally {
            try {
                val report = f.queue.shutdown()
                if (expectBumpIssue) {
                    assertEquals(1, report.issues.size)
                    assertEquals(LifecycleStage.STOP_SERVICES, report.issues.single().stage)
                    assertSame(f.failure(), report.issues.single().cause)
                } else assertTrue(report.isComplete)
            } finally {
                try { assertTrue(f.messages.close().isComplete) } finally {
                    try { f.session.stop() } finally {
                        try { f.store.close() } finally {
                            try { f.job.cancelAndJoin() } finally {
                                try {
                                    f.database.openHelper.writableDatabase.execSQL(
                                        "DROP TRIGGER IF EXISTS wp501_attempt_bump_failure")
                                } finally { f.database.close() }
                            }
                        }
                    }
                }
            }
        }
    }

    private class Fixture(val test: TestScope, private val attempts: Long) {
        val job = SupervisorJob(test.coroutineContext[Job])
        private val scope = CoroutineScope(test.coroutineContext + job)
        private val instant = Instant.ofEpochSecond(1_704_067_200)
        private val clock = object : SessionClock {
            override val now: Duration get() = test.testScheduler.currentTime.milliseconds
            override val wallClock: Clock = Clock.fixed(instant, ZoneOffset.UTC)
            override suspend fun sleepFor(duration: Duration) = delay(duration)
        }
        val device = DeviceDTO(
            id = UUID.randomUUID(), radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32) { 1 }),
            nodeName = "Test Device", firmwareVersion = 0u, firmwareVersionString = "", manufacturerName = "", buildDate = "",
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
        val radio = device.radioId
        private val token = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), radio)
        val contact = ContactDTO(
            id = UUID.randomUUID(), radioId = radio, publicKey = Bytes(ByteArray(32) { 2 }), name = "Test Contact",
            typeRawValue = 0u, flags = 0u, outPathLength = 255u, outPath = Bytes.EMPTY,
            lastAdvertTimestamp = 0u, latitude = 0.0, longitude = 0.0, lastModified = 0u,
            lastHeardTimestamp = 0u, nickname = null, isBlocked = false, isMuted = false,
            isFavorite = false, lastMessageDate = null, unreadCount = 0, unreadMentionCount = 0,
            ocvPreset = null, customOCVArrayString = null, avatarImageData = null,
        )
        val database = openMemoryDatabase()
        val store = RoomPersistenceStore(database, scope, clock.wallClock)
        val transport = FirmwareRadio(device.publicKey).also {
            it.respond = { error("The original unstarted session must not write any command") }
        }
        val session = MeshCoreSession(transport, clock = clock, coroutineContext = scope.coroutineContext)
        private val signals = Signals(token)
        private val diagnostics = mutableListOf<MessagingDiagnostic>()
        private val reporter = MessagingIssueReporter { diagnostics += it }
        val messages = MessageService(token, device.publicKey, session, store, signals, scope, clock = clock, reporter = reporter)
        val queue = ChatSendQueueService(token, store, messages,
            MessagingChannelQuery { error("DM attempt originals must not query channels") },
            OutgoingChannelReactionIndexer { _, _, _, _, _ -> error("DM attempt originals must not index reactions") },
            signals, scope, clock = clock, reporter = reporter)
        lateinit var message: MessageDTO
        lateinit var pending: PendingSendDTO
        val key get() = EntityKey(radio, message.id)

        suspend fun seed() {
            assertNotEquals(device.id, radio.value)
            store.saveDevice(device)
            store.saveContact(contact)
            assertEquals(device, store.fetchDevice(device.id))
            assertEquals(contact, store.fetchContact(EntityKey(radio, contact.id)))
            val created = messages.createPendingMessage("Hello", contact)
            val literal = MessageDTO(
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
            assertEquals(literal, created)
            store.updateMessageTimestamp(EntityKey(radio, created.id), 1_704_063_600u)
            message = literal.copy(timestamp = 1_704_063_600u)
            assertEquals(message, store.fetchMessage(key))
            pending = PendingSendDTO(UUID.randomUUID(), radio, message.id, PendingSendKind.DM, contact.id,
                null, false, "", 0u, null, 1, instant, attempts)
            store.upsertPendingSend(pending)
            assertEquals(listOf(pending), store.fetchPendingSends(radio))
            assertEquals(DirectMessageEnvelope(message.id, contact.id, false), pending.directMessageEnvelope())
        }

        suspend fun startDrain() { queue.hydrate(); test.runCurrent(); signals.ready(); test.runCurrent() }
        suspend fun awaitAttempt(count: Long, requireFreshTimestamp: Boolean) {
            val deadline = test.testScheduler.currentTime + 10_000
            while (true) {
                val row = store.fetchPendingSends(radio).singleOrNull()
                val stamp = store.fetchMessage(key)?.timestamp
                if (row?.attemptCount == count &&
                    (!requireFreshTimestamp || stamp != null && stamp != message.timestamp)) return
                assertTrue("Original ten-second drain deadline expired", test.testScheduler.currentTime < deadline)
                test.advanceTimeBy(10)
                test.runCurrent()
            }
        }
        fun failure() = diagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
            .single { it.operation == "park.${message.id}" }.cause
        suspend fun assertParked(row: PendingSendDTO, expected: MessageDTO) {
            assertEquals(listOf(row), store.fetchPendingSends(radio))
            assertTrue(store.hasPendingSend(key))
            assertEquals(expected, store.fetchMessage(key))
            assertEquals(1, queue.queuedEnvelopeCount)
            assertNull(session.currentSelfInfo)
            assertFalse(transport.isConnected())
            assertEquals(0, transport.connects)
            assertEquals(0, transport.readers)
            assertTrue(transport.sent.isEmpty())
        }

        private class Signals(private val token: SessionToken) : ConnectionSignals {
            private val subscriptions = mutableSetOf<Channel<ConnectionSnapshot>>()
            override val snapshot = MutableStateFlow(value(DeviceConnectionState.CONNECTING))
            private fun value(state: DeviceConnectionState) = ConnectionSnapshot(state, ConnectionState.Disconnected,
                null, ConnectionIntent.WantsConnection(), token, null)
            fun ready() {
                snapshot.value = value(DeviceConnectionState.READY)
                subscriptions.toList().forEach { it.trySend(snapshot.value).getOrThrow() }
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
}
