package one.meshcore.shared.persistence

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.ChatThreadStore
import one.meshcore.shared.chat.IngestStore
import one.meshcore.shared.chat.MessageStore
import one.meshcore.shared.chat.PendingSendDto
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.model.Uuid

/** The DAOs [RoomChatStore] reads and writes through, grouped so its constructor stays readable. */
data class ChatDaos(
    val message: MessageDao,
    val contact: ContactDao,
    val channel: ChannelDao,
    val messageRepeat: MessageRepeatDao,
    val pendingSend: PendingSendDao,
    val reaction: ReactionDao,
    val device: DeviceDao? = null,
)

/** Production chat-store bundle, including the device dao. */
internal fun MeshDatabase.chatDaos(): ChatDaos = ChatDaos(
    message = messageDao(),
    contact = contactDao(),
    channel = channelDao(),
    messageRepeat = messageRepeatDao(),
    pendingSend = pendingSendDao(),
    reaction = reactionDao(),
    device = deviceDao(),
)

/**
 * The Room-backed persistence adapter the per-connection chat graph reads and writes, scoped to one
 * [radioID]. It implements the three narrow seams the chat services depend on — [MessageStore]
 * (the send path), [IngestStore] (the receive path), and [ChatThreadStore] (the coordinator + thread
 * reads/writes) — mirroring the Swift single `PersistenceStore` that conforms to the same protocol
 * subset. Overlapping methods (message fetch/save, last-message stamping) share one body.
 *
 * One mutex serializes the read-modify-write of every guarded status write and every counter bump so
 * two concurrent updates cannot lose an increment or clobber a terminal row — the Room analogue of the
 * Swift `@ModelActor`'s serial isolation, as [RoomPendingSendStore] does for its sequence counter. The
 * paged thread fetches return the window in chronological (oldest-first) order, the shape the coordinator
 * prepends.
 */
class RoomChatStore internal constructor(private val parts: RoomChatParts) :
    MessageStore by parts.messages,
    IngestStore by parts.ingest,
    ChatThreadStore by parts.thread {
    constructor(
        radioID: Uuid,
        daos: ChatDaos,
        rxLogCorrelator: RxLogCorrelator,
        newContactId: () -> Uuid = Uuid::random,
        discoveredNodes: DiscoveredNodeStore? = null,
    ) : this(
        RoomChatParts(
            initialRadioId = radioID,
            daos = daos,
            rxLogCorrelator = rxLogCorrelator,
            newContactId = newContactId,
            discoveredNodes = discoveredNodes,
        ),
    )

    internal var radioID: Uuid
        get() = parts.radioID
        set(value) {
            parts.radioID = value
        }

    override suspend fun fetchMessage(id: Uuid): MessageDto? = parts.messages.fetchMessage(id)

    override suspend fun saveMessage(message: MessageDto) = parts.messages.saveMessage(message)

    override suspend fun updateContactLastMessage(contactID: Uuid, dateEpochSeconds: Double) =
        parts.messages.updateContactLastMessage(contactID, dateEpochSeconds)

    override suspend fun updateChannelLastMessage(channelID: Uuid, dateEpochSeconds: Double) =
        parts.messages.updateChannelLastMessage(channelID, dateEpochSeconds)

    override suspend fun replacePendingSendForRetry(dto: PendingSendDto): Int =
        parts.messageRecords.replacePendingSendForRetry(dto)
}
