package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestScope
import one.meshcore.shared.meshcore.ChannelMessage
import one.meshcore.shared.meshcore.ContactMessage
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger

/** Fixed epoch-second clock the ingest tests read through the injected `now`. */
internal const val INGEST_NOW_SECONDS: Double = 1_700_000_000.0

/** The sender key prefix the fake store resolves the default test contact from. */
internal val TEST_SENDER_PREFIX: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6)

/**
 * A controllable [IngestSession] for the poller tests: [queue] backlog results the drain pops in order
 * (empty means no more), and [emit] pushes live events onto the stream. The Kotlin stand-in for the
 * Swift mock-transport session.
 */
internal class FakeIngestSession : IngestSession {
    private val eventsFlow = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<MeshEvent> = eventsFlow.asSharedFlow()

    private val backlog = ArrayDeque<GetMessageResult>()
    var getMessageCalls = 0
        private set

    /** When set, every getMessage awaits this before returning, so a test can hold the drain suspended. */
    var getMessageGate: CompletableDeferred<Unit>? = null

    /** Appends [results] to the backlog the drain will pop. */
    fun queue(vararg results: GetMessageResult) = results.forEach { backlog.addLast(it) }

    override suspend fun getMessage(): GetMessageResult {
        getMessageCalls += 1
        getMessageGate?.await()
        return backlog.removeFirstOrNull() ?: GetMessageResult.NoMoreMessages
    }

    suspend fun emit(event: MeshEvent) = eventsFlow.emit(event)
}

/** Records every notifier call so a test can assert what was posted vs suppressed. */
internal class RecordingNotifier : IngestNotifier {
    val directPosts = mutableListOf<Uuid>()
    val channelPosts = mutableListOf<Int>()
    val unresolvedChannels = mutableListOf<Int>()
    val suppressHistory = mutableListOf<Boolean>()
    val removedDeliveredForContactIDs = mutableListOf<Uuid>()
    var badgeUpdates = 0
    var conversationsChanged = 0
    var isSuppressingNotifications: Boolean = false
        private set

    override fun setSyncNotificationSuppression(suppress: Boolean) {
        isSuppressingNotifications = suppress
        suppressHistory += suppress
    }

    override suspend fun postDirectMessage(
        contact: ResolvedContact,
        text: String,
        messageID: Uuid,
        hasSelfMention: Boolean,
    ) {
        directPosts += contact.id
    }

    override suspend fun postChannelMessage(notice: ChannelMessageNotice) {
        channelPosts += notice.channelIndex
    }

    override suspend fun recordUnresolvedChannel(channelIndex: Int, radioID: Uuid, senderTimestamp: Long) {
        unresolvedChannels += channelIndex
    }

    override suspend fun updateBadge() {
        badgeUpdates += 1
    }

    override suspend fun removeDeliveredForContact(contactID: Uuid) {
        removedDeliveredForContactIDs += contactID
    }

    override suspend fun notifyConversationsChanged() {
        conversationsChanged += 1
    }
}

/** A fixed active conversation for suppression tests. */
internal class FixedActiveConversation(
    private val contactID: Uuid? = null,
    private val channel: ActiveChannel? = null,
) : ActiveConversation {
    override suspend fun activeContactID(): Uuid? = contactID
    override suspend fun activeChannel(): ActiveChannel? = channel
}

/** Builds an incoming direct-message wire frame with the fields the ingest tests vary. */
@Suppress("LongParameterList")
internal fun contactMessage(
    text: String,
    senderTimestamp: Long,
    senderPrefix: ByteArray = TEST_SENDER_PREFIX,
    textType: Int = TextType.PLAIN.raw,
    pathLength: Int = 3,
    snr: Double? = -5.0,
): ContactMessage = ContactMessage(
    senderPublicKeyPrefix = senderPrefix,
    pathLength = pathLength,
    textType = textType,
    senderTimestamp = senderTimestamp,
    text = text,
    snr = snr,
)

/** Builds an incoming channel-message wire frame. */
@Suppress("LongParameterList")
internal fun channelMessageFrame(
    text: String,
    senderTimestamp: Long,
    channelIndex: Int,
    textType: Int = TextType.PLAIN.raw,
    pathLength: Int = 2,
    snr: Double? = -8.0,
): ChannelMessage = ChannelMessage(
    channelIndex = channelIndex,
    pathLength = pathLength,
    textType = textType,
    senderTimestamp = senderTimestamp,
    text = text,
    snr = snr,
)

/** A resolved contact for seeding the fake store. */
internal fun resolvedContact(
    id: Uuid = ChatFixtures.CONTACT,
    displayName: String = "Alice",
    isBlocked: Boolean = false,
    isMuted: Boolean = false,
): ResolvedContact = ResolvedContact(id = id, displayName = displayName, isBlocked = isBlocked, isMuted = isMuted)

/** A resolved channel for seeding the fake store. */
internal fun resolvedChannel(
    id: Uuid = ChatFixtures.freshUuid(),
    index: Int,
    name: String = "General",
    notificationLevel: NotificationLevel = NotificationLevel.ALL,
): ResolvedChannel = ResolvedChannel(
    id = id,
    index = index,
    radioID = ChatFixtures.RADIO,
    name = name,
    notificationLevel = notificationLevel,
)

/**
 * Fault-ingest pipeline shared by the dedup, stamp, and RX-log tests. The caller supplies the store
 * that throws; the logger records into [recorded] under the sync category.
 */
internal fun TestScope.ingestFaultPipeline(
    store: IngestStore,
    recorded: MutableList<DebugLogEntryDto>,
): MessageIngestPipeline = MessageIngestPipeline(
    collaborators = MessageIngestCollaborators(
        radioID = ChatFixtures.RADIO,
        store = store,
        blockedSenders = BlockedSendersCache(),
        broadcaster = IncomingMessageBroadcaster(),
        selfNodeName = { "" },
        now = { INGEST_NOW_SECONDS },
        newMessageId = SequentialIds()::next,
    ),
    edges = MessageIngestEdges(
        logger = PersistentLogger(
            scope = this,
            category = "SyncCoordinator",
            record = { recorded += it },
        ),
    ),
)
