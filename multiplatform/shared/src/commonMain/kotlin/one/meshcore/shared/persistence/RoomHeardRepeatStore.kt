package one.meshcore.shared.persistence

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.chat.pathdetail.HeardRepeatStore
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.Uuid

/**
 * Room-backed [HeardRepeatStore]: correlates RX-log echoes to sent channel rows and persists
 * [MessageRepeatEntity] + `heardRepeats` counters. Mirrors the Swift
 * `PersistenceStore` subset used by `HeardRepeatsService`.
 */
class RoomHeardRepeatStore(private val messageDao: MessageDao, private val messageRepeatDao: MessageRepeatDao) :
    HeardRepeatStore {
    override suspend fun messageRepeatExists(rxLogEntryID: Uuid): Boolean =
        messageRepeatDao.existsByRxLogEntryId(rxLogEntryID)

    override suspend fun findSentChannelMessage(
        radioID: Uuid,
        channelIndex: Int,
        timestamp: Long,
        text: String,
    ): MessageDto? = messageDao
        .findSentChannelMessage(
            radioID = radioID,
            channelIndex = channelIndex,
            timestamp = timestamp,
            directionRawValue = MessageDirection.OUTGOING.raw,
            text = text,
        )
        ?.toDto()

    override suspend fun findIncomingChannelMessage(radioID: Uuid, deduplicationKey: String): MessageDto? =
        messageDao.findIncomingChannelByDedup(radioID, deduplicationKey, MessageDirection.INCOMING.raw)?.toDto()

    override suspend fun adoptIncomingPathIfUnknown(messageID: Uuid, pathNodes: ByteArray, pathLength: Int): Boolean =
        messageDao.adoptIncomingPathIfUnknown(messageID, pathNodes, pathLength) > 0

    override suspend fun saveMessageRepeat(repeat: MessageRepeatDto) {
        messageRepeatDao.upsert(repeat.toEntity())
    }

    override suspend fun incrementMessageHeardRepeats(messageID: Uuid): Int {
        messageDao.incrementHeardRepeats(messageID)
        return messageDao.heardRepeats(messageID) ?: 0
    }

    override suspend fun fetchMessageRepeats(messageID: Uuid): List<MessageRepeatDto> =
        messageRepeatDao.listByMessage(messageID).map { it.toDto() }
}
