package one.meshcore.shared.simulator

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ReactionEntity

internal fun MockDataProvider.reactedMessages(): List<ReactedMessageSeed> = listOf(
    ReactedMessageSeed(aliceReactedMessageID, "👍:2,❤️:1"),
    ReactedMessageSeed(bayAreaReactedMessageID, "🎉:2"),
    ReactedMessageSeed(bayAreaMentionMessageID, "👍:1"),
)

internal fun MockDataProvider.reactions(messageID: Uuid, now: Double): List<ReactionEntity> = when (messageID) {
    aliceReactedMessageID -> aliceReactions(messageID, now)
    bayAreaReactedMessageID -> bayAreaReactions(messageID, now)
    bayAreaMentionMessageID -> mentionReaction(messageID, now)
    else -> emptyList()
}

private fun MockDataProvider.aliceReactions(messageID: Uuid, now: Double): List<ReactionEntity> = listOf(
    reaction(
        ReactionDraft(
            id = "A0000000-0000-0000-0000-000000000001",
            messageID = messageID,
            emoji = "👍",
            sender = "You",
            now = now,
            contactID = aliceChenID,
        ),
    ),
    reaction(
        ReactionDraft(
            id = "A0000000-0000-0000-0000-000000000002",
            messageID = messageID,
            emoji = "👍",
            sender = "Bob Martinez",
            now = now,
            contactID = aliceChenID,
        ),
    ),
    reaction(
        ReactionDraft(
            id = "A0000000-0000-0000-0000-000000000003",
            messageID = messageID,
            emoji = "❤️",
            sender = "Alice Chen",
            now = now,
            contactID = aliceChenID,
        ),
    ),
)

private fun MockDataProvider.bayAreaReactions(messageID: Uuid, now: Double): List<ReactionEntity> = listOf(
    reaction(
        ReactionDraft(
            id = "A1000000-0000-0000-0000-000000000001",
            messageID = messageID,
            emoji = "🎉",
            sender = "Alice Chen",
            now = now,
            channelIndex = BAY_AREA_CHANNEL_INDEX,
        ),
    ),
    reaction(
        ReactionDraft(
            id = "A1000000-0000-0000-0000-000000000002",
            messageID = messageID,
            emoji = "🎉",
            sender = "Bob Martinez",
            now = now,
            channelIndex = BAY_AREA_CHANNEL_INDEX,
        ),
    ),
)

private fun MockDataProvider.mentionReaction(messageID: Uuid, now: Double): List<ReactionEntity> = listOf(
    reaction(
        ReactionDraft(
            id = "A1000000-0000-0000-0000-000000000003",
            messageID = messageID,
            emoji = "👍",
            sender = "Sim",
            now = now,
            channelIndex = BAY_AREA_CHANNEL_INDEX,
        ),
    ),
)

private const val MESSAGE_HASH_PREFIX_LENGTH = 8

private fun MockDataProvider.reaction(draft: ReactionDraft): ReactionEntity = ReactionEntity(
    id = Uuid.normalized(draft.id),
    messageID = draft.messageID,
    emoji = draft.emoji,
    senderName = draft.sender,
    messageHash = draft.messageID.value.take(MESSAGE_HASH_PREFIX_LENGTH),
    rawText = draft.emoji,
    receivedAt = draft.now,
    radioID = simulatorDeviceID,
    channelIndex = draft.channelIndex,
    contactID = draft.contactID,
)

private data class ReactionDraft(
    val id: String,
    val messageID: Uuid,
    val emoji: String,
    val sender: String,
    val now: Double,
    val contactID: Uuid? = null,
    val channelIndex: Int? = null,
)
