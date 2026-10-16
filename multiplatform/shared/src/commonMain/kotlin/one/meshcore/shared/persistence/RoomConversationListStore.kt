package one.meshcore.shared.persistence

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.chat.ChannelPreviewKey
import one.meshcore.shared.chat.ConversationListStore
import one.meshcore.shared.chat.ConversationListWrites
import one.meshcore.shared.chat.FailedSendConversationKeys
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/** Room tables the conversation list reads and writes for one radio. */
data class RoomConversationDaos(
    val contact: ContactDao,
    val channel: ChannelDao,
    val message: MessageDao,
    val remoteNodeSession: RemoteNodeSessionDao,
    val roomMessage: RoomMessageDao,
    val pendingSend: PendingSendDao,
    val reaction: ReactionDao,
    val messageRepeat: MessageRepeatDao,
)

/** Production conversation-list bundle. */
internal fun MeshDatabase.conversationDaos(): RoomConversationDaos = RoomConversationDaos(
    contact = contactDao(),
    channel = channelDao(),
    message = messageDao(),
    remoteNodeSession = remoteNodeSessionDao(),
    roomMessage = roomMessageDao(),
    pendingSend = pendingSendDao(),
    reaction = reactionDao(),
    messageRepeat = messageRepeatDao(),
)

/**
 * Room-backed [ConversationListStore], the read-and-write seam [ConversationListViewModel] drives. The
 * per-radio fetches read the whole conversation surface (the view model applies the repeater/blocked/mask
 * filter), the batch preview fetches return each conversation's most recent rows in ascending time so the
 * newest is the last element, and the per-conversation writes are optimistic Room mutations. Chat pins
 * are local for contacts, channels, and rooms; a contact's separate favorite remains device-synced by
 * `ContactService`.
 *
 * Mute, pin, clear, and leave delegate to [RoomConversationListWrites], which serializes each
 * read-modify-write. Scoped to one [radioID], the connection's data partition.
 */
class RoomConversationListStore(internal var radioID: Uuid, daos: RoomConversationDaos) :
    ConversationListStore,
    ConversationListWrites by RoomConversationListWrites(daos) {
    private val contactDao = daos.contact
    private val channelDao = daos.channel
    private val messageDao = daos.message
    private val remoteNodeSessionDao = daos.remoteNodeSession
    private val roomMessageDao = daos.roomMessage

    override suspend fun fetchConversationContacts(radioID: Uuid): List<ContactDto> =
        contactDao.listConversations(radioID).map { it.toDto() }

    override suspend fun fetchContactIDs(radioID: Uuid): Set<Uuid> =
        contactDao.listByRadio(radioID).map { it.id }.toSet()

    override suspend fun fetchChannels(radioID: Uuid): List<ChannelDto> =
        channelDao.listByRadio(radioID).map { it.toDto() }

    override suspend fun fetchRooms(radioID: Uuid): List<RemoteNodeSessionDto> =
        remoteNodeSessionDao.listByRadio(radioID).map { it.toDto() }

    override suspend fun fetchFailedSendConversationKeys(radioID: Uuid): FailedSendConversationKeys {
        val failed = MessageStatus.FAILED.raw
        val outgoing = MessageDirection.OUTGOING.raw
        val contactIDs = messageDao.failedSendContactIds(radioID, failed, outgoing).toSet()
        val channelIndexes = messageDao.failedSendChannelIndexes(radioID, failed, outgoing).toSet()
        val channelIDs = if (channelIndexes.isEmpty()) {
            emptySet()
        } else {
            channelDao.listByRadio(radioID)
                .filter { it.index in channelIndexes }
                .map { it.id }
                .toSet()
        }
        val sessionIDs = remoteNodeSessionDao.listByRadio(radioID).map { it.id }
        val roomSessionIDs = if (sessionIDs.isEmpty()) {
            emptySet()
        } else {
            roomMessageDao.failedSendSessionIds(sessionIDs, failed).toSet()
        }
        return FailedSendConversationKeys(
            contactIDs = contactIDs,
            channelIDs = channelIDs,
            roomSessionIDs = roomSessionIDs,
        )
    }

    override suspend fun fetchLastContactMessages(contactIDs: List<Uuid>, limit: Int): Map<Uuid, List<MessageDto>> =
        contactIDs.associateWith { contactID ->
            messageDao.listDirectNewestFirst(radioID, contactID, limit, offset = 0).map { it.toDto() }.reversed()
        }

    override suspend fun fetchLastChannelMessages(
        channels: List<ChannelPreviewKey>,
        limit: Int,
    ): Map<Uuid, List<MessageDto>> = channels.associate { key ->
        key.id to
            messageDao.listChannelNewestFirst(key.radioID, key.channelIndex, limit, offset = 0)
                .map { it.toDto() }
                .reversed()
    }
}
