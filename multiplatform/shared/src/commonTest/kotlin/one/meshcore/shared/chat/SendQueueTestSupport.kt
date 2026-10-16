package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger

/**
 * In-memory [PendingSendStore] backing the send-queue tests, mirroring the behavior of the Room-backed
 * store: per-radio monotonic sequence assignment under a lock, the three-state `attemptCount` bump, and
 * the sequence-ordered fetch. [incrementFault], when set, makes [incrementPendingSendAttemptCount] throw
 * so the bump-failure park branch is exercised (the Kotlin analog of the Swift fault-injection hook).
 */
@Suppress("TooManyFunctions")
internal class InMemoryPendingSendStore : PendingSendStore {
    private val mutex = Mutex()
    private val rows = mutableListOf<PendingSendDto>()

    var incrementFault: (() -> Throwable)? = null

    /**
     * Radios that still have a Device row. `null` keeps every pending send so queue tests that never
     * insert a Device do not lose rows on [purgeOrphanPendingSends].
     */
    var knownRadioIDs: Set<Uuid>? = null

    override suspend fun insertPendingSendAssigningSequence(dto: PendingSendDto): Int = mutex.withLock {
        val seq = (rows.filter { it.radioID == dto.radioID }.maxOfOrNull { it.sequence } ?: 0) + 1
        rows.add(dto.copy(sequence = seq))
        seq
    }

    override suspend fun upsertPendingSend(dto: PendingSendDto) = mutex.withLock {
        rows.removeAll { it.id == dto.id }
        rows.add(dto)
        Unit
    }

    override suspend fun fetchPendingSends(radioID: Uuid): List<PendingSendDto> =
        mutex.withLock { rows.filter { it.radioID == radioID }.sortedBy { it.sequence } }

    override suspend fun fetchPendingSendsForMessage(messageID: Uuid): List<PendingSendDto> =
        mutex.withLock { rows.filter { it.messageID == messageID }.sortedBy { it.sequence } }

    override suspend fun deletePendingSend(id: Uuid) = mutex.withLock {
        rows.removeAll { it.id == id }
        Unit
    }

    override suspend fun deletePendingSendsForMessage(messageID: Uuid) = mutex.withLock {
        rows.removeAll { it.messageID == messageID }
        Unit
    }

    override suspend fun hasPendingSend(messageID: Uuid): Boolean =
        mutex.withLock { rows.any { it.messageID == messageID } }

    override suspend fun incrementPendingSendAttemptCount(messageID: Uuid): Int? {
        incrementFault?.let { throw it() }
        return mutex.withLock {
            val index = rows.indexOfFirst { it.messageID == messageID }
            if (index < 0) return@withLock null
            val next = (rows[index].attemptCount ?: 0) + 1
            rows[index] = rows[index].copy(attemptCount = next)
            next
        }
    }

    override suspend fun purgeLegacyAttemptCountRows(): Int = mutex.withLock {
        val count = rows.count { it.attemptCount == null }
        rows.removeAll { it.attemptCount == null }
        count
    }

    override suspend fun purgeOrphanPendingSends(): Int = mutex.withLock {
        val known = knownRadioIDs ?: return@withLock 0
        val count = rows.count { it.radioID !in known }
        rows.removeAll { it.radioID !in known }
        count
    }
}

/**
 * Composes an [InMemoryPendingSendStore] with an [InMemoryMessageStore] and an in-memory contact map into
 * the full [SendQueueStore] the queue drains against. The message store is shared with the [MessageService]
 * under test so a drain's status writes and the send path see one row set.
 */
internal class FakeSendQueueStore(
    val pending: InMemoryPendingSendStore = InMemoryPendingSendStore(),
    val messages: InMemoryMessageStore = InMemoryMessageStore(),
) : SendQueueStore,
    PendingSendStore by pending {
    val contacts = mutableMapOf<Uuid, ContactDto>()
    var fetchPendingSendsError: (() -> Throwable)? = null
    var insertError: (() -> Throwable)? = null
    var replaceError: (() -> Throwable)? = null
    var hasPendingSendOverride: Boolean? = null
    var fetchContactError: (() -> Throwable)? = null
    var deletePendingSendsError: (() -> Throwable)? = null

    override suspend fun fetchPendingSends(radioID: Uuid): List<PendingSendDto> {
        fetchPendingSendsError?.let { throw it() }
        return pending.fetchPendingSends(radioID)
    }

    override suspend fun insertPendingSendAssigningSequence(dto: PendingSendDto): Int {
        insertError?.let { throw it() }
        return pending.insertPendingSendAssigningSequence(dto)
    }

    override suspend fun hasPendingSend(messageID: Uuid): Boolean =
        hasPendingSendOverride ?: pending.hasPendingSend(messageID)

    override suspend fun fetchContact(id: Uuid): ContactDto? {
        fetchContactError?.let { throw it() }
        return contacts[id]
    }

    override suspend fun deletePendingSendsForMessage(messageID: Uuid) {
        deletePendingSendsError?.let { throw it() }
        pending.deletePendingSendsForMessage(messageID)
    }

    override suspend fun updateMessageStatus(id: Uuid, status: MessageStatus) = messages.updateMessageStatus(id, status)

    override suspend fun updateMessageStatusUnlessDelivered(id: Uuid, status: MessageStatus): Boolean =
        messages.updateMessageStatusUnlessDelivered(id, status)

    override suspend fun replacePendingSendForRetry(messageID: Uuid, dto: PendingSendDto): Int {
        replaceError?.let { throw it() }
        pending.deletePendingSendsForMessage(messageID)
        messages.updateMessageStatusUnlessDelivered(messageID, MessageStatus.PENDING)
        return pending.insertPendingSendAssigningSequence(dto)
    }
}

/** A [ChannelResolver] whose answer (or thrown fault) each drain reads, for the NOT_FOUND disambiguation tests. */
internal class FakeChannelResolver(var exists: Boolean = true, var fault: (() -> Throwable)? = null) : ChannelResolver {
    var calls = 0
        private set

    override suspend fun channelExists(index: Int): Boolean {
        calls += 1
        fault?.let { throw it() }
        return exists
    }
}

/** A deterministic pending-send row id generator distinct from the message-id space. */
internal class PendingSendIds {
    private var counter = 0
    fun next(): Uuid {
        counter += 1
        return Uuid.normalized("EEEEEEEE-0000-0000-0000-" + counter.toString().padStart(12, '0'))
    }
}

/** A logging send queue on the caller's clock, so a fault test can vary only the store. */
internal fun loggedSendQueue(
    scope: CoroutineScope,
    recorded: MutableList<DebugLogEntryDto>,
    store: FakeSendQueueStore,
    radioID: Uuid,
    now: Double,
): ChatSendQueueService = ChatSendQueueService(
    radioID = radioID,
    collaborators = ChatSendQueueCollaborators(
        store = store,
        messageService = MessageService(
            FakeChatSession(),
            store.messages,
            scope,
            MessageServiceIdentity({ now }, SequentialIds()::next),
        ),
        channelResolver = FakeChannelResolver(),
        scope = scope,
        now = { now },
        newPendingSendId = PendingSendIds()::next,
    ),
    edges = ChatSendQueueEdges(
        logger = PersistentLogger(
            scope = scope,
            category = "ChatSendQueueService",
            record = { recorded += it },
        ),
    ),
)
