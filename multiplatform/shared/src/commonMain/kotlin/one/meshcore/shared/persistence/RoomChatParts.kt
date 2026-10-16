package one.meshcore.shared.persistence

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.chat.ChannelSendConfiguration
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatCoordinatorStore
import one.meshcore.shared.chat.ChatThreadStore
import one.meshcore.shared.chat.ChatThreadUnreadStore
import one.meshcore.shared.chat.IngestBookkeeping
import one.meshcore.shared.chat.IngestStore
import one.meshcore.shared.chat.MessageDeliveryWrites
import one.meshcore.shared.chat.MessageStatusGuards
import one.meshcore.shared.chat.MessageStore
import one.meshcore.shared.chat.MessageWindow
import one.meshcore.shared.chat.PendingSendDto
import one.meshcore.shared.chat.ResolvedChannel
import one.meshcore.shared.chat.ResolvedContact
import one.meshcore.shared.chat.RxLogCorrelation
import one.meshcore.shared.chat.reorderSameSenderClusters
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.contact.toContactFrame
import one.meshcore.shared.contact.upsertDiscoveredFromLocalContact
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/** Shared Room handles and the one mutex every chat seam locks. */
internal class RoomChatParts(
    initialRadioId: Uuid,
    daos: ChatDaos,
    val rxLogCorrelator: RxLogCorrelator,
    val newContactId: () -> Uuid,
    val discoveredNodes: DiscoveredNodeStore?,
) {
    var radioID: Uuid = initialRadioId

    val writeMutex = Mutex()
    val messageDao = daos.message
    val contactDao = daos.contact
    val channelDao = daos.channel
    val messageRepeatDao = daos.messageRepeat
    val pendingSendDao = daos.pendingSend
    val reactionDao = daos.reaction
    val deviceDao = daos.device

    val delivery: MessageDeliveryWrites = RoomChatDelivery(this)
    val messageRecords = RoomChatMessages(this)
    val messages: MessageStore = messageRecords
    val bookkeeping: IngestBookkeeping = RoomChatBookkeeping(this)
    val ingest: IngestStore = RoomChatIngest(this)
    val threadReads: ChatCoordinatorStore = RoomChatThreadReads(this)
    val threadUnread: ChatThreadUnreadStore = RoomChatThreadUnread(this)
    val thread: ChatThreadStore = RoomChatThread(this)
}

internal class RoomChatDelivery(private val parts: RoomChatParts) : MessageDeliveryWrites {
    override suspend fun updateMessageStatus(id: Uuid, status: MessageStatus) = parts.writeMutex.withLock {
        val current = parts.messageDao.getById(id) ?: return@withLock
        parts.messageDao.upsert(current.withDeliveryStatus(status))
    }

    override suspend fun updateMessageStatusUnlessDelivered(id: Uuid, status: MessageStatus): Boolean =
        parts.writeMutex.withLock {
            val current = parts.messageDao.getById(id) ?: return@withLock false
            if (!MessageStatusGuards.allowsUnlessDelivered(current.status)) return@withLock false
            parts.messageDao.upsert(current.withDeliveryStatus(status))
            true
        }

    override suspend fun clearRetryingToSent(id: Uuid, onlyRetrying: Boolean): Boolean = parts.writeMutex.withLock {
        val current = parts.messageDao.getById(id) ?: return@withLock false
        if (!MessageStatusGuards.allowsClearRetryingToSent(current.status, onlyRetrying)) return@withLock false
        parts.messageDao.upsert(current.copy(statusRawValue = MessageStatus.SENT.raw))
        true
    }

    override suspend fun updateMessageRetryStatus(
        id: Uuid,
        status: MessageStatus,
        retryAttempt: Int,
        maxRetryAttempts: Int,
    ) = parts.writeMutex.withLock {
        val current = parts.messageDao.getById(id) ?: return@withLock
        if (!MessageStatusGuards.allowsRetryStatus(current.status)) return@withLock
        parts.messageDao.upsert(
            current.copy(statusRawValue = status.raw, retryAttempt = retryAttempt, maxRetryAttempts = maxRetryAttempts),
        )
    }

    override suspend fun updateMessageAck(id: Uuid, ackCode: Long, status: MessageStatus, roundTripTime: Long?) =
        parts.writeMutex.withLock {
            val current = parts.messageDao.getById(id) ?: return@withLock
            if (!MessageStatusGuards.allowsAckWrite(current.status, status)) return@withLock
            parts.messageDao.upsert(
                current.copy(ackCode = ackCode, statusRawValue = status.raw, roundTripTime = roundTripTime),
            )
        }

    override suspend fun updateMessageTimestamp(id: Uuid, timestamp: Long) = parts.writeMutex.withLock {
        val current = parts.messageDao.getById(id) ?: return@withLock
        parts.messageDao.upsert(current.copy(timestamp = timestamp))
    }

    override suspend fun incrementMessageSendCount(id: Uuid): Int {
        parts.messageDao.incrementSendCount(id)
        return parts.messageDao.sendCount(id) ?: 0
    }

    override suspend fun updateMessageHeardRepeats(id: Uuid, heardRepeats: Int) = parts.writeMutex.withLock {
        val current = parts.messageDao.getById(id) ?: return@withLock
        parts.messageDao.upsert(current.copy(heardRepeats = heardRepeats))
    }

    override suspend fun deleteMessageRepeats(messageID: Uuid) = parts.messageRepeatDao.deleteByMessage(messageID)
}

internal class RoomChatMessages(private val parts: RoomChatParts) :
    MessageStore,
    MessageDeliveryWrites by parts.delivery {
    override suspend fun fetchMessage(id: Uuid): MessageDto? = parts.messageDao.getById(id)?.toDto()

    override suspend fun saveMessage(message: MessageDto) = parts.messageDao.upsert(message.toEntity())

    override suspend fun updateContactLastMessage(contactID: Uuid, dateEpochSeconds: Double) {
        val owner = currentCoroutineContext().job
        parts.writeMutex.withLock {
            parts.contactDao.updateChatContactFields(contactID, owner) { current ->
                current.copy(lastMessageDate = dateEpochSeconds)
            }
        }
    }

    override suspend fun fetchDeviceNodeName(radioID: Uuid): String? = parts.deviceDao?.nodeNameByRadio(radioID)

    override suspend fun fetchChannelSendConfiguration(radioID: Uuid, channelIndex: Int): ChannelSendConfiguration {
        val channel = parts.channelDao.getByRadioAndIndex(radioID, channelIndex)?.toDto()
        val device = parts.deviceDao?.listByRadio(radioID)?.firstOrNull()?.toDto()
        return ChannelSendConfiguration(channel, device)
    }

    override suspend fun fetchChannelId(radioID: Uuid, channelIndex: Int): Uuid? =
        parts.channelDao.getByRadioAndIndex(radioID, channelIndex)?.id

    override suspend fun updateChannelLastMessage(channelID: Uuid, dateEpochSeconds: Double) =
        parts.writeMutex.withLock {
            val current = parts.channelDao.getById(channelID) ?: return@withLock
            parts.channelDao.upsertPreservingPin(current.copy(lastMessageDate = dateEpochSeconds))
        }

    override suspend fun saveContact(radioID: Uuid, contact: MeshContact) {
        val owner = currentCoroutineContext().job
        parts.writeMutex.withLock {
            parts.contactDao.saveContactFrame(radioID, contact.toContactFrame(), owner, parts.newContactId)
        }
    }

    override suspend fun hasOutgoingSentDM(ackCode: Long): Boolean =
        parts.messageDao.existsOutgoingDMWithAck(ackCode, MessageStatus.SENT.raw, MessageDirection.OUTGOING.raw)

    /**
     * The queue row and the status flip commit together, so the manual-retry window cannot be interrupted
     * halfway. The shared mutex is held so the status flip serializes with the other guarded status writes
     * the same way they serialize with each other.
     */
    override suspend fun replacePendingSendForRetry(dto: PendingSendDto): Int = parts.writeMutex.withLock {
        parts.messageDao.replacePendingSendForRetry(dto.toEntity())
    }
}

internal class RoomChatBookkeeping(private val parts: RoomChatParts) : IngestBookkeeping {
    override suspend fun updateContactLastMessage(contactID: Uuid, dateEpochSeconds: Double) =
        parts.messages.updateContactLastMessage(contactID, dateEpochSeconds)

    override suspend fun updateChannelLastMessage(channelID: Uuid, dateEpochSeconds: Double) =
        parts.messages.updateChannelLastMessage(channelID, dateEpochSeconds)

    override suspend fun incrementContactUnread(contactID: Uuid) {
        val owner = currentCoroutineContext().job
        parts.writeMutex.withLock {
            parts.contactDao.updateChatContactFields(contactID, owner) { current ->
                current.copy(unreadCount = current.unreadCount + 1)
            }
        }
    }

    override suspend fun incrementContactUnreadMention(contactID: Uuid) {
        val owner = currentCoroutineContext().job
        parts.writeMutex.withLock {
            parts.contactDao.updateChatContactFields(contactID, owner) { current ->
                current.copy(unreadMentionCount = current.unreadMentionCount + 1)
            }
        }
    }

    override suspend fun incrementChannelUnread(channelID: Uuid) = parts.writeMutex.withLock {
        val current = parts.channelDao.getById(channelID) ?: return@withLock
        parts.channelDao.upsertPreservingPin(current.copy(unreadCount = current.unreadCount + 1))
    }

    override suspend fun incrementChannelUnreadMention(channelID: Uuid) = parts.writeMutex.withLock {
        val current = parts.channelDao.getById(channelID) ?: return@withLock
        parts.channelDao.upsertPreservingPin(current.copy(unreadMentionCount = current.unreadMentionCount + 1))
    }

    override suspend fun touchContactHeard(contactID: Uuid, atEpochSeconds: Long) {
        val owner = currentCoroutineContext().job
        parts.writeMutex.withLock {
            val updated = parts.contactDao.updateChatContactFields(contactID, owner) { current ->
                val stamp = maxOf(current.lastHeardTimestamp ?: 0L, atEpochSeconds)
                if (current.lastHeardTimestamp != stamp) current.copy(lastHeardTimestamp = stamp) else null
            } ?: return@withLock
            val nodes = parts.discoveredNodes ?: return@withLock
            val stamp = requireNotNull(updated.lastHeardTimestamp)
            upsertDiscoveredFromLocalContact(
                radioID = updated.radioID,
                contact = updated.toDto(),
                store = nodes,
                nowEpochSeconds = { stamp },
            )
        }
    }
}

internal class RoomChatIngest(private val parts: RoomChatParts) :
    IngestStore,
    IngestBookkeeping by parts.bookkeeping {
    override suspend fun resolveContact(radioID: Uuid, senderKeyPrefix: ByteArray): ResolvedContact? {
        val row = parts.contactDao.listByRadio(radioID)
            .firstOrNull { it.publicKey.startsWith(senderKeyPrefix) } ?: return null
        val name = row.nickname?.takeIf { it.isNotBlank() } ?: row.name
        return ResolvedContact(id = row.id, displayName = name, isBlocked = row.isBlocked, isMuted = row.isMuted)
    }

    override suspend fun resolveChannel(radioID: Uuid, channelIndex: Int): ResolvedChannel? {
        val row = parts.channelDao.getByRadioAndIndex(radioID, channelIndex) ?: return null
        return ResolvedChannel(
            id = row.id,
            index = row.index,
            radioID = row.radioID,
            name = row.name,
            notificationLevel = row.notificationLevel,
        )
    }

    override suspend fun lookupRxLog(
        radioID: Uuid,
        channelIndex: Int?,
        senderTimestamp: Long,
        senderKeyPrefix: ByteArray?,
        defaultPathLength: Int,
    ): RxLogCorrelation = parts.rxLogCorrelator.correlate(
        radioID,
        channelIndex,
        senderTimestamp,
        senderKeyPrefix,
        defaultPathLength,
    )

    override suspend fun isDuplicateMessage(deduplicationKey: String, radioID: Uuid): Boolean =
        parts.messageDao.countByDedup(radioID, deduplicationKey) > 0

    override suspend fun saveMessage(message: MessageDto) = parts.messages.saveMessage(message)
}

internal class RoomChatThreadReads(private val parts: RoomChatParts) : ChatCoordinatorStore {
    override suspend fun fetchMessage(id: Uuid): MessageDto? = parts.messages.fetchMessage(id)

    // Every paged fetch restores send order inside a same-sender burst the mesh relayed out of order, so a
    // thread reads the same on the first paint as it does after paging back (Swift PersistenceStore).
    override suspend fun fetchDirectMessages(contactID: Uuid, limit: Int, offset: Int): List<MessageDto> =
        reorderSameSenderClusters(
            parts.messageDao.listDirectNewestFirst(parts.radioID, contactID, limit, offset).map {
                it.toDto()
            }.reversed(),
        )

    override suspend fun fetchChannelMessages(
        radioID: Uuid,
        channelIndex: Int,
        limit: Int,
        offset: Int,
    ): List<MessageDto> = reorderSameSenderClusters(
        parts.messageDao.listChannelNewestFirst(radioID, channelIndex, limit, offset).map { it.toDto() }.reversed(),
    )

    override suspend fun fetchMessageWindow(
        conversation: ChatConversationID,
        anchorSortDate: Double?,
        floorLimit: Int,
    ): MessageWindow {
        val limit = windowLimit(conversation, anchorSortDate, floorLimit)
        val newestFirst = when (val key = conversation.conversation) {
            is ChatConversationID.ConversationKey.Dm ->
                parts.messageDao.listDirectNewestFirst(parts.radioID, key.contactID, limit + HAS_MORE_PROBE, 0)
            is ChatConversationID.ConversationKey.Channel ->
                parts.messageDao.listChannelNewestFirst(
                    conversation.radioID,
                    key.channelIndex,
                    limit + HAS_MORE_PROBE,
                    0,
                )
        }
        val hasMore = newestFirst.size > limit
        val page = newestFirst.take(limit).map { it.toDto() }.reversed()
        return MessageWindow(messages = reorderSameSenderClusters(page), hasMore = hasMore)
    }

    private suspend fun windowLimit(conversation: ChatConversationID, anchorSortDate: Double?, floorLimit: Int): Int {
        if (anchorSortDate == null) return floorLimit
        val count = when (val key = conversation.conversation) {
            is ChatConversationID.ConversationKey.Dm ->
                parts.messageDao.countDirectAtOrNewer(parts.radioID, key.contactID, anchorSortDate)
            is ChatConversationID.ConversationKey.Channel ->
                parts.messageDao.countChannelAtOrNewer(conversation.radioID, key.channelIndex, anchorSortDate)
        }
        return maxOf(floorLimit, count)
    }

    private companion object {
        /** Fetching one row beyond the limit distinguishes "exactly limit rows exist" from "more remain". */
        const val HAS_MORE_PROBE = 1
    }
}

internal class RoomChatThreadUnread(private val parts: RoomChatParts) : ChatThreadUnreadStore {
    override suspend fun fetchUnreadCount(conversation: ChatConversationID): Int? =
        when (val key = conversation.conversation) {
            is ChatConversationID.ConversationKey.Dm -> parts.contactDao.getById(key.contactID)?.unreadCount
            is ChatConversationID.ConversationKey.Channel ->
                parts.channelDao.getByRadioAndIndex(conversation.radioID, key.channelIndex)?.unreadCount
        }

    override suspend fun clearUnread(conversation: ChatConversationID) {
        val owner = currentCoroutineContext().job
        parts.writeMutex.withLock {
            when (val key = conversation.conversation) {
                is ChatConversationID.ConversationKey.Dm -> {
                    parts.contactDao.updateChatContactFields(key.contactID, owner) { current ->
                        current.copy(unreadCount = 0, unreadMentionCount = 0)
                    }
                }
                is ChatConversationID.ConversationKey.Channel -> {
                    val current = parts.channelDao.getByRadioAndIndex(conversation.radioID, key.channelIndex)
                        ?: return@withLock
                    parts.channelDao.upsertPreservingPin(current.copy(unreadCount = 0, unreadMentionCount = 0))
                }
            }
        }
    }

    override suspend fun markMessageAsRead(id: Uuid) {
        parts.messageDao.markMessageAsRead(id)
    }

    override suspend fun clearUnreadCount(conversation: ChatConversationID) {
        val owner = currentCoroutineContext().job
        parts.writeMutex.withLock {
            when (val key = conversation.conversation) {
                is ChatConversationID.ConversationKey.Dm -> {
                    parts.contactDao.updateChatContactFields(key.contactID, owner) { current ->
                        current.copy(unreadCount = 0)
                    }
                }
                is ChatConversationID.ConversationKey.Channel -> {
                    val current = parts.channelDao.getByRadioAndIndex(conversation.radioID, key.channelIndex)
                        ?: return@withLock
                    parts.channelDao.upsertPreservingPin(current.copy(unreadCount = 0))
                }
            }
        }
    }
}

internal class RoomChatThread(private val parts: RoomChatParts) :
    ChatThreadStore,
    ChatCoordinatorStore by parts.threadReads,
    ChatThreadUnreadStore by parts.threadUnread {

    override suspend fun fetchMessageRepeats(messageID: Uuid): List<MessageRepeatDto> =
        parts.messageRepeatDao.listByMessage(messageID).map { it.toDto() }

    /**
     * Drops the message with every row that hangs off it. No entity declares a foreign key, so all three
     * child deletes are explicit, including the message repeats SwiftData removes through the
     * `Message -> MessageRepeat` cascade rule. Each child row is reaped whether or not the message row
     * still exists: a queue upsert racing a delete can strand a pending send, and a stranded pending send
     * is replayed on the next connect, broadcasting text the user deleted.
     */
    override suspend fun deleteMessage(id: Uuid) {
        val ids = listOf(id)
        parts.pendingSendDao.deleteByMessageIds(ids)
        parts.reactionDao.deleteByMessageIds(ids)
        parts.messageRepeatDao.deleteByMessageIds(ids)
        parts.messageDao.deleteById(id)
    }

    override suspend fun recomputeContactLastMessageDate(contactID: Uuid) {
        val owner = currentCoroutineContext().job
        val radioID = parts.radioID
        parts.writeMutex.withLock {
            parts.contactDao.recomputeContactLastMessageDate(radioID, contactID, parts.messageDao, owner)
        }
    }

    override suspend fun markMessageFailedUnlessDelivered(id: Uuid): Boolean =
        parts.delivery.updateMessageStatusUnlessDelivered(id, MessageStatus.FAILED)

    override suspend fun markMentionSeen(id: Uuid) = parts.writeMutex.withLock {
        parts.messageDao.markMentionSeen(id)
    }

    override suspend fun markConversationMentionsSeen(conversation: ChatConversationID): List<Uuid> =
        parts.writeMutex.withLock {
            when (val key = conversation.conversation) {
                is ChatConversationID.ConversationKey.Dm -> {
                    val ids = parts.messageDao.unseenDirectMentionIds(key.contactID)
                    if (ids.isNotEmpty()) parts.messageDao.markDirectMentionsSeen(key.contactID)
                    ids
                }
                is ChatConversationID.ConversationKey.Channel -> {
                    val ids = parts.messageDao.unseenChannelMentionIds(conversation.radioID, key.channelIndex)
                    if (ids.isNotEmpty()) {
                        parts.messageDao.markChannelMentionsSeen(conversation.radioID, key.channelIndex)
                    }
                    ids
                }
            }
        }

    override suspend fun replacePendingSendForRetry(dto: PendingSendDto): Int =
        parts.messageRecords.replacePendingSendForRetry(dto)

    override suspend fun markFailedSendsSeen(conversation: ChatConversationID) = parts.writeMutex.withLock {
        val failed = MessageStatus.FAILED.raw
        val outgoing = MessageDirection.OUTGOING.raw
        when (val key = conversation.conversation) {
            is ChatConversationID.ConversationKey.Dm ->
                parts.messageDao.markFailedSendsSeenForContact(key.contactID, failed, outgoing)
            is ChatConversationID.ConversationKey.Channel ->
                parts.messageDao.markFailedSendsSeenForChannel(
                    conversation.radioID,
                    key.channelIndex,
                    failed,
                    outgoing,
                )
        }
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun MessageEntity.withDeliveryStatus(status: MessageStatus): MessageEntity = copy(
    statusRawValue = status.raw,
    failureSeen = MessageStatusGuards.failureSeenAfterWrite(this.status, status, failureSeen),
)
