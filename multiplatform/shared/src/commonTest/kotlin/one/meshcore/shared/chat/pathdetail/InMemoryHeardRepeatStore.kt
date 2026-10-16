package one.meshcore.shared.chat.pathdetail

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.Uuid

/** In-memory heard-repeat store for correlation and deduplication tests. */
class InMemoryHeardRepeatStore : HeardRepeatStore {
    private val mutex = Mutex()
    private val messages = mutableMapOf<Uuid, MessageDto>()
    private val repeats = mutableListOf<MessageRepeatDto>()
    private val heardRepeatCounts = mutableMapOf<Uuid, Int>()

    suspend fun seedMessage(message: MessageDto) = mutex.withLock {
        messages[message.id] = message
        heardRepeatCounts[message.id] = message.heardRepeats
    }

    override suspend fun messageRepeatExists(rxLogEntryID: Uuid): Boolean = mutex.withLock {
        repeats.any { it.rxLogEntryID == rxLogEntryID }
    }

    override suspend fun findSentChannelMessage(
        radioID: Uuid,
        channelIndex: Int,
        timestamp: Long,
        text: String,
    ): MessageDto? = mutex.withLock {
        messages.values.firstOrNull {
            it.radioID == radioID &&
                it.channelIndex == channelIndex &&
                it.timestamp == timestamp &&
                it.direction == MessageDirection.OUTGOING &&
                it.text == text
        }
    }

    override suspend fun findIncomingChannelMessage(radioID: Uuid, deduplicationKey: String): MessageDto? =
        mutex.withLock {
            messages.values.firstOrNull {
                it.radioID == radioID &&
                    it.deduplicationKey == deduplicationKey &&
                    it.channelIndex != null &&
                    it.direction == MessageDirection.INCOMING
            }
        }

    override suspend fun adoptIncomingPathIfUnknown(messageID: Uuid, pathNodes: ByteArray, pathLength: Int): Boolean =
        mutex.withLock {
            val message = messages[messageID] ?: return@withLock false
            if (message.pathNodes != null) return@withLock false
            messages[messageID] = message.copy(pathNodes = pathNodes, pathLength = pathLength)
            true
        }

    override suspend fun saveMessageRepeat(repeat: MessageRepeatDto) = mutex.withLock {
        val index = repeats.indexOfFirst { it.id == repeat.id }
        if (index >= 0) repeats[index] = repeat else repeats += repeat
    }

    override suspend fun incrementMessageHeardRepeats(messageID: Uuid): Int = mutex.withLock {
        if (!messages.containsKey(messageID)) return@withLock 0
        val next = (heardRepeatCounts[messageID] ?: 0) + 1
        heardRepeatCounts[messageID] = next
        messages[messageID]?.let { messages[messageID] = it.copy(heardRepeats = next) }
        next
    }

    override suspend fun fetchMessageRepeats(messageID: Uuid): List<MessageRepeatDto> = mutex.withLock {
        repeats.filter { it.messageID == messageID }.sortedBy { it.receivedAt }
    }

    suspend fun heardRepeats(messageID: Uuid): Int = mutex.withLock { heardRepeatCounts[messageID] ?: 0 }
}
