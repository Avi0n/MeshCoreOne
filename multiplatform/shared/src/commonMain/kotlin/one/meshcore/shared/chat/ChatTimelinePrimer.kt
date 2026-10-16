package one.meshcore.shared.chat

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.reactions.ReactionParser
import one.meshcore.shared.model.Uuid

/**
 * Speculative warm path for a closed conversation's shared [ChatCoordinator]. Loads an unread-sized
 * first page so an open can paint from a populated coordinator instead of a cold fetch. Mirrors iOS
 * `ChatTimelinePrimer` / `prefetchConversation`. Rooms have no coordinator.
 *
 * [prefetch] skips an already-loaded coordinator (list-tap). [prime] always replaces unless the
 * chat is open, matching iOS arrival-time [ChatPrewarmRefresher] which re-primes a warm tail.
 *
 * A channel prime loads sender tables and JPEG bytes, then bakes [MessageItem]s onto the coordinator
 * so the next open's first composition can paint bubbles, matching iOS `bakeAll` before the first body.
 */
class ChatTimelinePrimer(private val hooks: Hooks) {
    data class Hooks(
        val registry: () -> ChatCoordinatorRegistry?,
        val store: () -> ChatCoordinatorStore?,
        val isConversationActive: suspend (ChatConversationID) -> Boolean,
        val unknownLabel: suspend () -> String,
        val warmPreviews: suspend (Conversation) -> Unit = {},
        val loadContacts: suspend (Uuid) -> List<ContactDto> = { emptyList() },
        val indexPage: suspend (Conversation, List<MessageDto>) -> Unit = { _, _ -> },
        val envInputs: suspend () -> EnvInputs = { EnvInputs.DEFAULT },
    )

    /**
     * List-tap prefetch. No-ops when the chat is open, already loaded, or a room.
     */
    suspend fun prefetch(conversation: Conversation) {
        val id = conversation.coordinatorID() ?: return
        if (hooks.isConversationActive(id)) return
        val existing = hooks.registry()?.existingCoordinator(id)
        if (existing != null && existing.renderState.phase == ChatRenderState.LoadPhase.LOADED) return
        prime(conversation)
    }

    /**
     * Replaces the coordinator's first page even when it is already loaded. Open chats are skipped
     * so this cannot race the live thread's optimistic rows.
     */
    suspend fun prime(conversation: Conversation) {
        val id = conversation.coordinatorID() ?: return
        if (hooks.isConversationActive(id)) return
        val registry = hooks.registry() ?: return
        val store = hooks.store() ?: return
        warmSenderTables(conversation)
        val coordinator = registry.coordinator(id)
        val writer = coordinator.bindWriter(this, ChatWriterRole.PRIME) ?: return
        val window = store.fetchMessageWindow(
            id,
            null,
            ChatCoordinator.initialPageSize(conversation.unreadCount),
        )
        if (hooks.isConversationActive(id) || !writer.isCurrent) return
        val isDM = conversation is Conversation.Direct
        val unfiltered = window.messages
        val isHidden: (MessageDto) -> Boolean = { message ->
            isHiddenOutgoingReaction(message) { ReactionParser.isSentReactionText(it.text, isDM) }
        }
        // Same unfiltered positional bake as ChatTimelinePopulator: a live open can present as soon as
        // this session's divider id matches a flag already on the warm items.
        val dividerMessageID = computeNewMessagesDivider(
            unfiltered,
            conversation.unreadCount,
            isDM,
            isHiddenReaction = { message, _ -> isHidden(message) },
        )
        val visible = unfiltered.filterNot(isHidden)
        val env = bakeEnv(conversation, hooks.envInputs())
        val tables = senderTables(conversation)
        val resolveSender = channelAwareSenderResolver(tables, unknownLabel = hooks.unknownLabel())
        val incomingAvatars = { tables().incomingAvatars }
        val inputs = buildThreadInputs(
            messages = visible,
            dividerMessageID = dividerMessageID,
            resolveSender = resolveSender,
            context = MessageBuildContext(
                currentUserName = env.currentUserName,
                envInputs = env,
                incomingAvatarTable = incomingAvatars,
            ),
        )
        val built = inputs.map { (message, perMessage) ->
            MessageFragmentBuilder.makeItem(message, perMessage, env)
        }
        writer.replaceAll(visible)
        writer.updateRenderState {
            it.copy(
                items = built,
                itemIndexByID = built.indexByID(),
                hasMoreMessages = window.hasMore,
                totalFetchedCount = unfiltered.size,
                isLoadingOlder = false,
            )
        }
        hooks.indexPage(conversation, unfiltered)
        hooks.warmPreviews(conversation)
    }

    private suspend fun warmSenderTables(conversation: Conversation) {
        if (conversation !is Conversation.Channel) return
        val contacts = hooks.loadContacts(conversation.channel.radioID)
        val tables = ChannelSenderTables.from(contacts)
        IncomingAvatarJPEGStore.replace(tables.contacts, tables.incomingAvatars.values)
        ChannelSenderTableStore.replace(conversation.channel.radioID, tables)
    }
}

private fun bakeEnv(conversation: Conversation, env: EnvInputs): EnvInputs {
    val channel = (conversation as? Conversation.Channel)?.channel
    return if (suppressesMapPreviews(channel)) env.copy(showMapPreviews = false) else env
}

private fun senderTables(conversation: Conversation): () -> ChannelSenderTables = {
    when (conversation) {
        is Conversation.Channel -> ChannelSenderTableStore.tables(conversation.channel.radioID)
        is Conversation.Direct, is Conversation.Room -> ChannelSenderTables.EMPTY
    }
}

private fun Conversation.coordinatorID(): ChatConversationID? = when (this) {
    is Conversation.Direct -> ChatConversationID.dm(contact.radioID, contact.id)
    is Conversation.Channel -> ChatConversationID.channel(channel.radioID, channel.index)
    is Conversation.Room -> null
}
