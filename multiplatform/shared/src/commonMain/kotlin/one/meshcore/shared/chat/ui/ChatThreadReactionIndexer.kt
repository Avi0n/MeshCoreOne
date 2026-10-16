package one.meshcore.shared.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.reactions.ReactionHandler
import one.meshcore.shared.chat.reactions.ReactionHandlerEdges
import one.meshcore.shared.chat.reactions.ReactionIndexScope
import one.meshcore.shared.chat.reactions.ReactionSummarySink
import one.meshcore.shared.chat.reactions.indexMessages
import one.meshcore.shared.model.Uuid
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Builds the page indexer that drains reactions queued against a message the load has just brought back
 * into memory, mirroring the iOS `indexMessagesForReactions` call after the initial page and every older
 * page. It notifies nobody: a reaction whose target only now appeared is old news, and iOS raises no banner
 * for it either. Summary refresh reaches the open thread through `ReactionService.summaryUpdates`, which the
 * view model already folds into a row reload, so the sink here is a no-op.
 *
 * A disconnected graph (no reaction service or store) indexes nothing; so does a conversation whose contact
 * has not resolved yet, since a drained reaction must be persisted under its own conversation's identity.
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun rememberReactionPageIndexer(
    environment: ChatThreadEnvironment,
    channel: ChannelDto?,
    contact: ContactDto?,
    unknownSenderLabel: String,
): suspend (List<MessageDto>) -> Unit {
    val service = environment.reactionService()
    val store = environment.reactionStore()
    val currentUnknownSenderLabel by rememberUpdatedState(unknownSenderLabel)
    val handler = remember(service, store) {
        if (service == null || store == null) {
            null
        } else {
            ReactionHandler(
                reactionService = service,
                store = store,
                sink = ReactionSummarySink { _, _ -> },
                newReactionId = ::randomReactionId,
                now = { Clock.System.now().toEpochMilliseconds() / MILLIS_PER_SECOND },
                edges = ReactionHandlerEdges(unknownSenderLabel = { currentUnknownSenderLabel }),
            )
        }
    }
    val currentHandler by rememberUpdatedState(handler)
    val currentChannel by rememberUpdatedState(channel)
    val currentContact by rememberUpdatedState(contact)
    val currentEnvironment by rememberUpdatedState(environment)
    return remember {
        { page ->
            val latestEnvironment = currentEnvironment
            val scope = reactionIndexScope(
                currentChannel,
                currentContact ?: latestEnvironment.currentContact(),
                latestEnvironment,
            )
            val latestHandler = currentHandler
            if (latestHandler != null && scope != null) latestHandler.indexMessages(page, scope)
        }
    }
}

/** The conversation identity a drained reaction is persisted under: a channel slot or a DM contact. */
private fun reactionIndexScope(
    channel: ChannelDto?,
    contact: ContactDto?,
    environment: ChatThreadEnvironment,
): ReactionIndexScope? = when {
    channel != null -> ReactionIndexScope.Channel(channel, environment.localNodeName())
    contact != null -> ReactionIndexScope.Direct(contact)
    else -> null
}

private fun randomReactionId(): Uuid = Uuid.random()

private const val MILLIS_PER_SECOND = 1_000.0
