package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.backup.ReactionDto
import one.meshcore.shared.model.Uuid

/** In-memory channel-message deletion store for cascade tests. */
class InMemoryChannelMessageDeletionStore internal constructor(private val parts: InMemoryChannelDeletionParts) :
    ChannelMessageDeletionStore by parts.records {
    constructor() : this(InMemoryChannelDeletionParts())

    suspend fun seedMessage(message: MessageDto) = parts.seeds.seedMessage(message)

    suspend fun seedReaction(reaction: ReactionDto) = parts.seeds.seedReaction(reaction)

    suspend fun seedRepeat(repeat: MessageRepeatDto) = parts.seeds.seedRepeat(repeat)

    suspend fun seedPendingSend(messageID: Uuid) = parts.seeds.seedPendingSend(messageID)

    suspend fun message(id: Uuid): MessageDto? = parts.seeds.message(id)

    suspend fun reactions(messageID: Uuid): List<ReactionDto> = parts.seeds.reactions(messageID)

    suspend fun repeats(messageID: Uuid): List<MessageRepeatDto> = parts.seeds.repeats(messageID)

    suspend fun hasPendingSend(messageID: Uuid): Boolean = parts.seeds.hasPendingSend(messageID)
}
