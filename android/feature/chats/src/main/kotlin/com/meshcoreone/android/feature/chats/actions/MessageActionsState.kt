// PortedFrom: MC1/Views/Chats/Reactions/ReactionDetailsSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Reactions/MessageActionsSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/BlockSenderSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.actions

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface MessageActionsDataSource {
    suspend fun contacts(radioId: RadioId): List<ContactDTO>
    suspend fun discoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO>
    suspend fun repeats(messageId: UUID): List<MessageRepeatDTO>
    suspend fun reactions(messageId: UUID): List<ReactionDTO>
    suspend fun blockChannelSender(radioId: RadioId, senderName: String)
    suspend fun blockContacts(contactIds: Set<UUID>)
    suspend fun setMuted(contactId: UUID, muted: Boolean)
}

sealed interface MessageActionsFailure {
    data object DetailsLoadFailed : MessageActionsFailure
    data object ReactionsLoadFailed : MessageActionsFailure
    data object BlockFailed : MessageActionsFailure
    data object MuteFailed : MessageActionsFailure
}

data class ReactionGroup(
    val emoji: String,
    val reactions: List<ReactionDTO>,
) {
    val count: Int get() = reactions.size
}

data class MessageActionsState(
    val isLoadingDetails: Boolean = false,
    val repeats: List<MessageRepeatDTO>? = null,
    val directory: MessagePathDirectory = MessagePathDirectory(),
    val reactionGroups: List<ReactionGroup> = emptyList(),
    val selectedEmoji: String? = null,
    val failure: MessageActionsFailure? = null,
    val blockedSender: String? = null,
    val mutedContactIds: Set<UUID> = emptySet(),
    val isLoadingReactions: Boolean = false,
    val reactionFailure: MessageActionsFailure.ReactionsLoadFailed? = null,
    val isReactionLoadCancelled: Boolean = false,
)

class MessageActionsStateHolder(
    private val dataSource: MessageActionsDataSource,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(MessageActionsState())
    val state: StateFlow<MessageActionsState> = mutableState.asStateFlow()
    private var reactionJob: Job? = null
    private var reactionRequest = 0L

    fun loadDetails(message: MessageDTO) {
        if (!MessageActionAvailability.forMessage(message).showsPathDetail) return
        scope.launch {
            mutableState.value = mutableState.value.copy(isLoadingDetails = true, failure = null)
            try {
                val contacts = dataSource.contacts(message.radioId)
                val discovered = dataSource.discoveredNodes(message.radioId)
                val repeats = dataSource.repeats(message.id)
                mutableState.value = mutableState.value.copy(
                    isLoadingDetails = false,
                    repeats = repeats,
                    directory = MessagePathDirectory(
                        contacts,
                        contacts.filter { it.isRepeater },
                        discovered.filter { it.nodeType.name == "REPEATER" },
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    isLoadingDetails = false,
                    repeats = emptyList(),
                    failure = MessageActionsFailure.DetailsLoadFailed,
                )
            }
        }
    }

    fun loadReactions(messageId: UUID, preferredEmoji: String?) {
        val request = ++reactionRequest
        reactionJob?.cancel()
        mutableState.value = mutableState.value.copy(
            isLoadingReactions = true,
            reactionGroups = emptyList(),
            selectedEmoji = null,
            reactionFailure = null,
            isReactionLoadCancelled = false,
            failure = mutableState.value.failure.takeUnless { it == MessageActionsFailure.ReactionsLoadFailed },
        )
        reactionJob = scope.launch {
            try {
                val groups = reactionGroups(dataSource.reactions(messageId))
                if (request != reactionRequest) return@launch
                mutableState.value = mutableState.value.copy(
                    reactionGroups = groups,
                    selectedEmoji = resolvedReactionSelection(preferredEmoji, groups.map { it.emoji }),
                )
            } catch (cancelled: CancellationException) {
                if (request == reactionRequest) {
                    mutableState.value = mutableState.value.copy(isReactionLoadCancelled = true)
                }
                throw cancelled
            } catch (_: Exception) {
                if (request != reactionRequest) return@launch
                mutableState.value = mutableState.value.copy(
                    reactionGroups = emptyList(),
                    selectedEmoji = null,
                    failure = MessageActionsFailure.ReactionsLoadFailed,
                    reactionFailure = MessageActionsFailure.ReactionsLoadFailed,
                )
            } finally {
                if (request == reactionRequest) {
                    mutableState.value = mutableState.value.copy(isLoadingReactions = false)
                }
            }
        }
    }

    fun selectEmoji(emoji: String) {
        if (mutableState.value.reactionGroups.any { it.emoji == emoji }) {
            mutableState.value = mutableState.value.copy(selectedEmoji = emoji)
        }
    }

    fun blockSender(message: MessageDTO, contactIds: Set<UUID>) {
        val sender = message.senderNodeName ?: return
        scope.launch {
            try {
                dataSource.blockChannelSender(message.radioId, sender)
                dataSource.blockContacts(contactIds)
                mutableState.value = mutableState.value.copy(blockedSender = sender, failure = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(failure = MessageActionsFailure.BlockFailed)
            }
        }
    }

    fun setMuted(contact: ContactDTO, muted: Boolean) {
        scope.launch {
            try {
                dataSource.setMuted(contact.id, muted)
                val ids = mutableState.value.mutedContactIds.toMutableSet()
                if (muted) ids += contact.id else ids -= contact.id
                mutableState.value = mutableState.value.copy(mutedContactIds = ids, failure = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(failure = MessageActionsFailure.MuteFailed)
            }
        }
    }
}

fun resolvedReactionSelection(preferred: String?, available: List<String>): String? =
    preferred?.takeIf(available::contains) ?: available.firstOrNull()

fun reactionGroups(reactions: List<ReactionDTO>): List<ReactionGroup> =
    reactions.groupBy { it.emoji }
        .map { ReactionGroup(it.key, it.value) }
        .sortedWith(
            compareByDescending<ReactionGroup> { it.count }
                .thenBy { group -> group.reactions.minOf { it.receivedAt } },
        )

data class ReactionIndexResult(
    val reactions: List<ReactionDTO>,
    val summary: String,
)

object ReactionIndexingPolicy {
    fun index(
        message: MessageDTO,
        candidates: List<ReactionDTO>,
        localNodeName: String?,
    ): ReactionIndexResult {
        if (message.isChannelMessage && message.isOutgoing && localNodeName == null) {
            return ReactionIndexResult(emptyList(), "")
        }
        val unique = candidates.distinctBy { Triple(it.senderName, it.emoji, it.messageID) }
        val counts = unique.groupingBy { it.emoji }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString(",") { "${it.key}:${it.value}" }
        return ReactionIndexResult(unique, counts)
    }
}
