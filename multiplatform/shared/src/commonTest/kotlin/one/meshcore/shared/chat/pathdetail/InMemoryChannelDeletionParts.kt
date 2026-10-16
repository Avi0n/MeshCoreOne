package one.meshcore.shared.chat.pathdetail

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.backup.ReactionDto
import one.meshcore.shared.model.Uuid

/** Shared rows and the one mutex the in-memory channel deletion store locks. */
internal class InMemoryChannelDeletionParts {
    val mutex = Mutex()
    val messages = mutableMapOf<Uuid, MessageDto>()
    val reactions = mutableListOf<ReactionDto>()
    val repeats = mutableListOf<MessageRepeatDto>()
    val pendingSendMessageIDs = mutableSetOf<Uuid>()

    val records: ChannelMessageDeletionStore = InMemoryChannelDeletionRecords(this)
    val seeds = InMemoryChannelDeletionSeeds(this)
}

internal class InMemoryChannelDeletionRecords(private val parts: InMemoryChannelDeletionParts) :
    ChannelMessageDeletionStore {
    override suspend fun channelMessageIdsFromSender(radioID: Uuid, senderName: String): List<Uuid> =
        parts.mutex.withLock {
            parts.messages.values
                .filter { it.radioID == radioID && it.channelIndex != null && it.senderNodeName == senderName }
                .map { it.id }
        }

    override suspend fun deletePendingSends(messageIDs: List<Uuid>) = parts.mutex.withLock {
        parts.pendingSendMessageIDs.removeAll(messageIDs.toSet())
        Unit
    }

    override suspend fun deleteReactions(messageIDs: List<Uuid>) = parts.mutex.withLock {
        val ids = messageIDs.toSet()
        parts.reactions.removeAll { it.messageID in ids }
        Unit
    }

    override suspend fun deleteMessageRepeats(messageIDs: List<Uuid>) = parts.mutex.withLock {
        val ids = messageIDs.toSet()
        parts.repeats.removeAll { it.messageID in ids }
        Unit
    }

    override suspend fun deleteMessages(messageIDs: List<Uuid>) = parts.mutex.withLock {
        messageIDs.forEach { parts.messages.remove(it) }
    }
}

internal class InMemoryChannelDeletionSeeds(private val parts: InMemoryChannelDeletionParts) {
    suspend fun seedMessage(message: MessageDto) = parts.mutex.withLock { parts.messages[message.id] = message }

    suspend fun seedReaction(reaction: ReactionDto) = parts.mutex.withLock { parts.reactions += reaction }

    suspend fun seedRepeat(repeat: MessageRepeatDto) = parts.mutex.withLock { parts.repeats += repeat }

    suspend fun seedPendingSend(messageID: Uuid) = parts.mutex.withLock { parts.pendingSendMessageIDs += messageID }

    suspend fun message(id: Uuid): MessageDto? = parts.mutex.withLock { parts.messages[id] }

    suspend fun reactions(messageID: Uuid): List<ReactionDto> =
        parts.mutex.withLock { parts.reactions.filter { it.messageID == messageID } }

    suspend fun repeats(messageID: Uuid): List<MessageRepeatDto> =
        parts.mutex.withLock { parts.repeats.filter { it.messageID == messageID } }

    suspend fun hasPendingSend(messageID: Uuid): Boolean =
        parts.mutex.withLock { messageID in parts.pendingSendMessageIDs }
}
