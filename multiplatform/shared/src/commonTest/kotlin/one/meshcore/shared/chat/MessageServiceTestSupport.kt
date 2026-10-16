package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.sequentialTestUuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.time.Duration

/** The expected ACK the fake session reports; distinct from any [AckCodeBuilder] prediction. */
internal val FAKE_ACK: ByteArray = byteArrayOf(0x11, 0x22, 0x33, 0x44)

/** Fixed epoch-second clock base the message-service tests read through the injected `now`. */
internal const val TEST_NOW_SECONDS: Double = 1_700_000_000.0

/**
 * A controllable [ChatSession] for the message-service tests: it records sends, can fail them, gates the
 * DM send on a deferred (to hold the send suspended), and answers ACK waits and contact queries from
 * fields the test sets. The Kotlin stand-in for the Swift `MockTransport`-backed session.
 */
internal class FakeChatSession(
    var selfPublicKey: ByteArray? = ByteArray(32) { 0x01 },
    var sentInfo: MessageSentInfo = MessageSentInfo(route = 0, expectedAck = FAKE_ACK, suggestedTimeoutMs = 5000),
) : ChatSession {
    private val eventsFlow = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<MeshEvent> = eventsFlow.asSharedFlow()

    /** Live subscriber count on the event stream, so a test can await the ACK listener being attached. */
    val eventSubscriptionCount: StateFlow<Int> get() = eventsFlow.subscriptionCount

    val directSendAttempts = mutableListOf<Int>()
    val channelSends = mutableListOf<Triple<Int, String, Long>>()
    val channelScopes = mutableListOf<ResolvedFloodScope>()
    var beforeScopedSend: ((ResolvedFloodScope) -> Unit)? = null

    /** When set, every DM send throws this; use a [ChatDeviceException] to exercise pool backoff. */
    var directSendError: (() -> Throwable)? = null

    /** When set, the DM send awaits this before returning, so a test can observe the suspended state. */
    var directSendGate: CompletableDeferred<Unit>? = null

    /** The result each `waitForAcknowledgement` returns; false drives the retry loop to exhaustion. */
    var ackArrives: Boolean = false

    var resetPathCalls = 0
        private set

    /** The number of DM sends that had happened when `resetPath` was first called. */
    var directSendsBeforeReset: Int? = null
        private set

    var contactToReturn: MeshContact? = null

    /** When set, `getContact` throws this instead of returning [contactToReturn]. */
    var contactError: (() -> Throwable)? = null

    /** When set, `currentSelfPublicKey` throws this before the direct send is attempted. */
    var selfPublicKeyError: (() -> Throwable)? = null

    /** When set, `resetPath` throws this so flood-escalate failure stays in flood mode. */
    var resetPathError: (() -> Throwable)? = null

    override suspend fun currentSelfPublicKey(): ByteArray? {
        selfPublicKeyError?.let { throw it() }
        return selfPublicKey
    }

    override suspend fun sendDirectMessage(
        destination: ByteArray,
        text: String,
        timestamp: Long,
        attempt: Int,
    ): MessageSentInfo {
        directSendError?.let { throw it() }
        directSendAttempts += attempt
        directSendGate?.await()
        return sentInfo
    }

    override suspend fun sendChannelMessage(
        channelIndex: Int,
        text: String,
        timestamp: Long,
        floodScope: ResolvedFloodScope,
    ) {
        channelScopes += floodScope
        beforeScopedSend?.invoke(floodScope)
        sendChannelMessage(channelIndex, text, timestamp)
    }

    override suspend fun sendChannelMessage(channelIndex: Int, text: String, timestamp: Long) {
        directSendError?.let { throw it() }
        channelSends += Triple(channelIndex, text, timestamp)
    }

    override suspend fun resetPath(publicKey: ByteArray) {
        resetPathError?.let { throw it() }
        if (directSendsBeforeReset == null) directSendsBeforeReset = directSendAttempts.size
        resetPathCalls += 1
    }

    override suspend fun getContact(publicKey: ByteArray): MeshContact? {
        contactError?.let { throw it() }
        return contactToReturn
    }

    override suspend fun waitForAcknowledgement(code: ByteArray, timeout: Duration): Boolean = ackArrives

    /** Pushes an event onto the session stream, as an inbound frame would (drives the ACK listener). */
    suspend fun emit(event: MeshEvent) = eventsFlow.emit(event)
}

/** A deterministic message-id generator so tests can predict and seed ids. */
internal class SequentialIds {
    private var counter = 0
    fun next(): Uuid {
        counter += 1
        return sequentialTestUuid(counter)
    }
}

/** Builds a [MessageService] wired to [session]/[store] with a fixed clock and deterministic ids. */
@Suppress("LongParameterList")
internal fun testMessageService(
    session: FakeChatSession,
    store: MessageStore,
    scope: CoroutineScope,
    config: MessageServiceConfig = MessageServiceConfig(),
    now: () -> Double = { TEST_NOW_SECONDS },
    ids: SequentialIds = SequentialIds(),
    logger: PersistentLogger = PersistentLogger(scope, category = "MessageService"),
): MessageService = MessageService(
    session = session,
    store = store,
    scope = scope,
    identity = MessageServiceIdentity(now = now, newMessageId = ids::next),
    config = config,
    logger = logger,
)

/** A message service that records under MessageService, so a fault test can read the logged line. */
internal fun loggedMessageService(
    scope: CoroutineScope,
    session: FakeChatSession,
    store: MessageStore,
    recorded: MutableList<DebugLogEntryDto>,
): MessageService = testMessageService(
    session,
    store,
    scope,
    logger = PersistentLogger(
        scope = scope,
        category = "MessageService",
        record = { recorded += it },
    ),
)

/** Drains every buffered event currently available on a status subscription without suspending. */
internal fun ReceiveChannel<MessageStatusEvent>.drain(): List<MessageStatusEvent> {
    val out = mutableListOf<MessageStatusEvent>()
    while (true) {
        val next = tryReceive().getOrNull() ?: break
        out += next
    }
    return out
}

internal fun List<MessageStatusEvent>.failedIds(): List<Uuid> =
    filterIsInstance<MessageStatusEvent.Failed>().map { it.messageID }

internal fun List<MessageStatusEvent>.resolvedTo(): List<Pair<Uuid, one.meshcore.shared.model.MessageStatus>> =
    filterIsInstance<MessageStatusEvent.StatusResolved>().map { it.messageID to it.status }

internal fun List<MessageStatusEvent>.resentIds(): List<Uuid> =
    filterIsInstance<MessageStatusEvent.Resent>().map { it.messageID }

internal fun List<MessageStatusEvent>.retryingEvents(): List<MessageStatusEvent.Retrying> =
    filterIsInstance<MessageStatusEvent.Retrying>()

internal fun List<MessageStatusEvent>.routingChanges(): List<MessageStatusEvent.RoutingChanged> =
    filterIsInstance<MessageStatusEvent.RoutingChanged>()

/** ACK code bytes whose little-endian uint32 the store persists; the reverse of [ByteArray.ackCodeLeUInt32]. */
internal fun ackBytes(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

// The service confines pending-ACK state behind an internal mutex; these mirror the Swift
// `setPendingAckForTest` / `pendingAckForTest` / `insertInFlightRetryForTest` hooks.

internal suspend fun MessageService.setPendingAck(ack: PendingAck) =
    stateMutex.withLock { pendingAcks[ack.messageID] = ack }

internal suspend fun MessageService.peekPendingAck(id: Uuid): PendingAck? = stateMutex.withLock { pendingAcks[id] }

internal suspend fun MessageService.insertInFlightRetry(id: Uuid) = stateMutex.withLock { inFlightRetries.add(id) }

/** A pending-ACK entry keyed off the fixed test clock; [ageSeconds] sets how long ago it was sent. */
@Suppress("LongParameterList")
internal fun pendingAck(
    messageID: Uuid,
    contactID: Uuid = ChatFixtures.CONTACT,
    codes: Set<Long> = setOf(FAKE_ACK.ackCodeLeUInt32()),
    ageSeconds: Double = 0.0,
    timeoutSeconds: Double = 30.0,
    isDelivered: Boolean = false,
    nowSeconds: Double = TEST_NOW_SECONDS,
): PendingAck = PendingAck(
    messageID = messageID,
    contactID = contactID,
    ackCodes = codes,
    sentAtEpochSeconds = nowSeconds - ageSeconds,
    timeoutSeconds = timeoutSeconds,
    isDelivered = isDelivered,
)
