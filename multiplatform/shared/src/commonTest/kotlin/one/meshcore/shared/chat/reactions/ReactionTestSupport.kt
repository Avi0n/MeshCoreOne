package one.meshcore.shared.chat.reactions

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.ReactionDto
import one.meshcore.shared.chat.ResolvedContact
import one.meshcore.shared.chat.isOutgoing
import one.meshcore.shared.chat.reactionTimestamp
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.model.Uuid

/**
 * An in-memory [ReactionStore] mirroring the SwiftData reaction queries against a plain list, so the
 * service and handler run with real matching logic and no Room. Candidate scans return most-recent first
 * (createdAt then timestamp descending) and window-filter on the message timestamp, as the Swift
 * `PersistenceStore` fetches do.
 */
class InMemoryReactionStore(
    private val messages: List<MessageDto> = emptyList(),
    contacts: List<ContactDto> = emptyList(),
    channels: List<ChannelDto> = emptyList(),
) : ReactionStore {
    val reactions = mutableListOf<ReactionDto>()
    val summaries = mutableMapOf<Uuid, String?>()
    private val contactsById = contacts.associateBy { it.id }
    private val channelsByRadioAndIndex = channels.associateBy { it.radioID to it.index }
    private val messagesById = messages.associateBy { it.id }

    override suspend fun saveReaction(reaction: ReactionDto) {
        reactions.removeAll { it.id == reaction.id }
        reactions += reaction
    }

    override suspend fun fetchReactions(messageID: Uuid, limit: Int): List<ReactionDto> =
        reactions.filter { it.messageID == messageID }.sortedByDescending { it.receivedAt }.take(limit)

    override suspend fun reactionExists(messageID: Uuid, senderName: String, emoji: String): Boolean =
        reactions.any { it.messageID == messageID && it.senderName == senderName && it.emoji == emoji }

    override suspend fun updateMessageReactionSummary(messageID: Uuid, summary: String?) {
        summaries[messageID] = summary
    }

    override suspend fun findDirectMessageForReaction(
        radioID: Uuid,
        contactID: Uuid,
        messageHash: String,
        window: LongRange,
        limit: Int,
    ): MessageDto? = directCandidates(radioID, contactID, window, limit).firstOrNull { candidate ->
        !ReactionParser.isReactionText(candidate.text, isDM = true) &&
            ReactionParser.generateMessageHash(candidate.text, candidate.reactionTimestamp) == messageHash
    }

    override suspend fun findChannelMessageForReaction(
        lookup: ChannelReactionLookup,
        window: LongRange,
        limit: Int,
    ): MessageDto? = channelCandidates(lookup.radioID, lookup.channelIndex, window, limit).firstOrNull { candidate ->
        val senderMatches = if (candidate.isOutgoing) {
            lookup.localNodeName != null && lookup.parsed.targetSender == lookup.localNodeName
        } else {
            candidate.senderNodeName == lookup.parsed.targetSender
        }
        senderMatches &&
            ReactionParser.generateMessageHash(candidate.text, candidate.reactionTimestamp) == lookup.parsed.messageHash
    }

    override suspend fun fetchDirectMessageCandidates(
        radioID: Uuid,
        contactID: Uuid,
        window: LongRange,
        limit: Int,
    ): List<MessageDto> = directCandidates(radioID, contactID, window, limit)

    override suspend fun fetchChannelMessageCandidates(
        radioID: Uuid,
        channelIndex: Int,
        window: LongRange,
        limit: Int,
    ): List<MessageDto> = channelCandidates(radioID, channelIndex, window, limit)

    override suspend fun fetchContact(id: Uuid): ContactDto? = contactsById[id]

    override suspend fun fetchChannel(radioID: Uuid, channelIndex: Int): ChannelDto? =
        channelsByRadioAndIndex[radioID to channelIndex]

    override suspend fun fetchMessage(id: Uuid): MessageDto? = messagesById[id]

    private fun directCandidates(radioID: Uuid, contactID: Uuid, window: LongRange, limit: Int): List<MessageDto> =
        messages.filter {
            it.radioID == radioID && it.contactID == contactID && it.timestamp in window
        }.recentFirst().take(limit)

    private fun channelCandidates(radioID: Uuid, channelIndex: Int, window: LongRange, limit: Int): List<MessageDto> =
        messages.filter {
            it.radioID == radioID && it.channelIndex == channelIndex && it.timestamp in window
        }.recentFirst().take(limit)

    private fun List<MessageDto>.recentFirst(): List<MessageDto> =
        sortedWith(compareByDescending<MessageDto> { it.createdAt }.thenByDescending { it.timestamp })
}

/** Records the reaction wire texts sent, and can be armed to throw so the failure path is exercised. */
class RecordingReactionSender(private val error: Throwable? = null) : ReactionSender {
    val channelSends = mutableListOf<Triple<String, Int, Uuid>>()
    val directSends = mutableListOf<Pair<String, ContactDto>>()

    override suspend fun sendChannelReaction(text: String, channelIndex: Int, radioID: Uuid) {
        error?.let { throw it }
        channelSends += Triple(text, channelIndex, radioID)
    }

    override suspend fun sendDirectReaction(text: String, contact: ContactDto) {
        error?.let { throw it }
        directSends += text to contact
    }
}

/** Collects reaction-summary updates the handler/service emit, keyed by message. */
class RecordingSummarySink : ReactionSummarySink {
    val updates = mutableListOf<Pair<Uuid, String>>()

    override suspend fun onReactionSummary(messageID: Uuid, summary: String) {
        updates += messageID to summary
    }

    fun summaryFor(messageID: Uuid): String? = updates.lastOrNull { it.first == messageID }?.second
}

/** Records every reaction notification the handler decided was warranted. */
class RecordingReactionNotifier : ReactionNotifier {
    data class Notified(
        val reactorName: String,
        val emoji: String,
        val messagePreview: String,
        val messageID: Uuid,
        val contactID: Uuid?,
        val channelIndex: Int?,
        val radioID: Uuid,
    )

    val notified = mutableListOf<Notified>()

    override suspend fun notifyReaction(notice: ReactionNotice) {
        notified += Notified(
            notice.reactorName,
            notice.emoji,
            notice.messagePreview,
            notice.messageID,
            notice.contactID,
            notice.channelIndex,
            notice.radioID,
        )
    }
}

/** Builds a [ResolvedContact] from a [ContactDto] for the DM receive path. */
fun ContactDto.resolved() = ResolvedContact(
    id = id,
    displayName = displayName,
    isBlocked = isBlocked,
    isMuted = isMuted,
)
