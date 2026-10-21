// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
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
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.session.SessionCorrelationException
import com.meshcoreone.android.core.services.contacts.ChannelService
import com.meshcoreone.android.core.services.contacts.ChannelServiceClock
import com.meshcoreone.android.core.services.contacts.ChannelServiceError
import com.meshcoreone.android.core.services.messaging.ChatSendQueueConfig
import com.meshcoreone.android.core.services.messaging.ChatSendQueueService
import com.meshcoreone.android.core.services.messaging.MessageService
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*

internal fun assertOriginalChannelDeviceError2(cause: Throwable) {
    assertTrue(cause is MessageServiceException)
    val failure = (cause as MessageServiceException).error
    assertTrue(failure is MessageServiceError.SessionError)
    val underlying = (failure as MessageServiceError.SessionError).underlying
    assertTrue(underlying is MeshCoreException.DeviceError)
    assertEquals(2.toUByte(), (underlying as MeshCoreException.DeviceError).code)
}

internal fun assertChannelQueryUnresolved(cause: Throwable, family: String) {
    val chain = generateSequence(cause) { it.cause }.take(16).toList()
    val shape = chain.joinToString(" -> ") { "${it.javaClass.name}(${it.message})" }
    assertTrue("Expected ConnectionLost for $family; cause chain: $shape", cause is MeshCoreException.ConnectionLost)
    val correlation = chain.dropWhile { it is MeshCoreException.ConnectionLost }.firstOrNull()
    assertTrue("Expected UnresolvedReply after ConnectionLost/recovery copies for $family; cause chain: $shape",
        correlation is SessionCorrelationException.UnresolvedReply)
    assertEquals("Wrong unresolved family; cause chain: $shape",
        family, (correlation as SessionCorrelationException.UnresolvedReply).responseFamily)
}

internal suspend fun TestScope.withChannelQueueFixture(
    attempts: Long,
    config: MessageServiceConfig = MessageServiceConfig(),
    gone: Boolean = false,
    queueConfig: ChatSendQueueConfig = ChatSendQueueConfig(),
    expectQueryIssue: Boolean = false,
    body: suspend (ChannelQueueOriginalFixture) -> Unit,
) {
    val f = ChannelQueueOriginalFixture(this, attempts, config, gone, queueConfig)
    var primaryFailure: Throwable? = null
    suspend fun retainFailure(action: suspend () -> Unit) {
        try { action() } catch (failure: Throwable) {
            val primary = primaryFailure
            if (primary == null) primaryFailure = failure
            else if (primary !== failure) primary.addSuppressed(failure)
        }
    }
    retainFailure {
        f.seed()
        body(f)
        assertEquals(listOf(f.device), f.store.fetchDevices())
        assertEquals(listOf(f.channel), f.store.fetchChannels(f.radio))
        assertTrue(f.store.fetchContacts(f.radio).isEmpty())
    }
    withContext(NonCancellable) {
        retainFailure {
            val report = f.queue.shutdown()
            if (expectQueryIssue) {
                assertEquals(1, report.issues.size)
                assertEquals(LifecycleStage.STOP_SERVICES, report.issues.single().stage)
                val issue = report.issues.single().cause
                assertSame("Shutdown must retain the original channel-query failure, not the later send failure",
                    f.failures("channelQuery", f.messageA.id).single(), issue)
                assertTrue("Expected retained ChannelService SessionError; got ${issue.stackTraceToString()}",
                    issue is ChannelServiceError.SessionError)
                assertChannelQueryUnresolved((issue as ChannelServiceError.SessionError).error, "channel:0")
            } else assertTrue(report.isComplete)
        }
        retainFailure { assertTrue(f.messages.close().isComplete) }
        retainFailure { f.session.stop() }
        retainFailure { f.store.close() }
        retainFailure { f.job.cancelAndJoin() }
        retainFailure { f.database.close() }
    }
    primaryFailure?.let { throw it }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class ChannelQueueOriginalFixture(
    val test: TestScope,
    private val attempts: Long,
    config: MessageServiceConfig,
    gone: Boolean,
    queueConfig: ChatSendQueueConfig,
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
        id = UUID.randomUUID(), radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32) { 0x11 }),
        nodeName = "Test Radio", firmwareVersion = 0u, firmwareVersionString = "", manufacturerName = "", buildDate = "",
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
    val channel = ChannelDTO(UUID.randomUUID(), radio, 0u, "Test Channel", Bytes(ByteArray(16) { 0xAA.toByte() }),
        true, null, 0, 0, NotificationLevel.ALL, false, "inherit", null)
    val database = openMemoryDatabase()
    val store = RoomPersistenceStore(database, scope, clock.wallClock)
    private val signals = Signals(token)
    private val diagnostics = mutableListOf<Pair<Long, MessagingDiagnostic>>()
    private val reporter = MessagingIssueReporter { diagnostics += test.testScheduler.currentTime to it }
    private val transport = FirmwareRadio(device.publicKey).also { radio ->
        radio.respond = { command ->
            when (command[0].toInt()) {
                1 -> listOf(Bytes.fromHex("05001414" + "11".repeat(32) +
                    "00000000000000000200020038f60d0090d003000a05546573744e6f6465"))
                3 -> listOf(Bytes.of(1, 2))
                31 -> if (gone) listOf(Bytes.fromHex("1200" + "00".repeat(48))) else listOf(Bytes.of(1, 2))
                else -> error("Unexpected command in channel original: ${command.hexString}")
            }
        }
    }
    val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 0.2), clock, scope.coroutineContext)
    val messages = MessageService(token, device.publicKey, session, store, signals, scope,
        config, clock, jitter = { 1.0 }, reporter = reporter)
    private val channelService = ChannelService(session, store, null, ChannelServiceClock { delay(it) }, Random(0))
    val queries = mutableListOf<UByte>()
    val queryResults = mutableListOf<ChannelInfo?>()
    val queue = ChatSendQueueService(token, store, messages, MessagingChannelQuery { index ->
        queries += index
        channelService.fetchChannel(index).also { queryResults += it }
    }, OutgoingChannelReactionIndexer { _, _, _, _, _ -> error("Failed originals must not index reactions") },
        signals, scope, queueConfig, clock, reporter)
    lateinit var messageA: MessageDTO
    lateinit var messageB: MessageDTO
    lateinit var pendingA: PendingSendDTO
    lateinit var pendingB: PendingSendDTO

    suspend fun seed() {
        assertNotEquals(device.id, radio.value)
        store.saveDevice(device)
        store.saveChannel(channel)
        assertEquals(device, store.fetchDevice(device.id))
        assertEquals(channel, store.fetchChannel(radio, 0u))
        session.start()
        assertEquals(SelfInfo(0u, 20, 20, device.publicKey, 0.0, 0.0, 2u, 0u, 0u, 0u, 2u,
            false, 915.0, 250.0, 10u, 5u, "TestNode"), session.currentSelfInfo)
        val first = messages.createPendingChannelMessage("Hello channel", 0u, radio)
        val second = messages.createPendingChannelMessage("Second message", 0u, radio)
        messageA = literalMessage(first.id, "Hello channel")
        messageB = literalMessage(second.id, "Second message")
        assertNotEquals(messageA.id, messageB.id)
        assertEquals(messageA, first)
        assertEquals(messageB, second)
        assertMessages(messageA, messageB)
        pendingA = PendingSendDTO(UUID.randomUUID(), radio, messageA.id, PendingSendKind.CHANNEL,
            null, 0u, true, "Hello channel", 1_704_067_200u, "Test Radio", 1, instant, attempts)
        pendingB = PendingSendDTO(UUID.randomUUID(), radio, messageB.id, PendingSendKind.CHANNEL,
            null, 0u, false, "Second message", 1_704_067_200u, "Test Radio", 2, instant, 2)
        store.upsertPendingSend(pendingA)
        assertRows(pendingA)
        assertEquals(ChannelMessageEnvelope(messageA.id, 0u, true, "Hello channel", 1_704_067_200u, "Test Radio"),
            pendingA.channelMessageEnvelope())
        assertEquals(ChannelMessageEnvelope(messageB.id, 0u, false, "Second message", 1_704_067_200u, "Test Radio"),
            pendingB.channelMessageEnvelope())
    }

    suspend fun persistB() { store.upsertPendingSend(pendingB); assertRows(pendingA, pendingB) }
    suspend fun startDrain() { queue.hydrate(); test.runCurrent(); signals.ready(); test.runCurrent() }
    suspend fun assertRows(vararg rows: PendingSendDTO) { assertEquals(rows.toList(), store.fetchPendingSends(radio)) }
    suspend fun assertMessages(a: MessageDTO, b: MessageDTO) {
        assertEquals(a, store.fetchMessage(EntityKey(radio, messageA.id)))
        assertEquals(b, store.fetchMessage(EntityKey(radio, messageB.id)))
    }
    fun sends(command: Int) = transport.sent.filter { it[0].toInt() == command }
    fun failures(phase: String, id: UUID) = diagnostics.map { it.second }
        .filterIsInstance<MessagingDiagnostic.Failure>().filter { it.operation == "$phase.$id" }.map { it.cause }
    fun timeOf(phase: String, id: UUID) = diagnostics.single {
        (it.second as? MessagingDiagnostic.Failure)?.operation == "$phase.$id"
    }.first

    private fun literalMessage(id: UUID, text: String) = MessageDTO(
        id = id, radioId = radio, contactID = null, channelIndex = 0u, text = text,
        timestamp = 1_704_067_200u, createdAt = instant, sortDate = instant,
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

    private class Signals(private val token: SessionToken) : ConnectionSignals {
        private val subscriptions = mutableSetOf<Channel<ConnectionSnapshot>>()
        override val snapshot = MutableStateFlow(value(DeviceConnectionState.CONNECTED))
        private fun value(state: DeviceConnectionState) = ConnectionSnapshot(state, ConnectionState.Connected,
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
