package one.meshcore.shared.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import one.meshcore.shared.chat.linkpreview.clear
import one.meshcore.shared.model.Uuid

/**
 * Claims the coordinator's writer slot as the interactive owner and installs render hooks so
 * coalesced reload and hard-reset feed this view model's item build. Revokes a closed-chat prime.
 */
internal fun ChatThreadViewModel.bind() {
    writer = coordinator.bindWriter(
        owner = this,
        role = ChatWriterRole.INTERACTIVE,
        renderItemRebuilder = { id -> rebuildRenderItem(id) },
        renderStateInvalidated = { buildItems() },
    )
    if (coordinator.renderState.items.isNotEmpty()) {
        publish()
    }
}

/**
 * Launches the ingest and status collectors that fold into the coordinator. A reconnect tears down
 * the previous ServiceContainer and finishes those flows, so a second [start] cancels the dead
 * jobs and binds the new ones — matching iOS `MessageEventDispatcher.wire` on each container.
 */
fun ChatThreadViewModel.start(incoming: Flow<IncomingMessageEvent>, status: Flow<MessageStatusEvent>) {
    eventJobs.forEach { it.cancel() }
    val jobs = mutableListOf(
        scope.launch { incoming.collect { handleIncoming(it) } },
        scope.launch { status.collect { handleStatus(it) } },
    )
    // Rebuild when a map thumbnail resolves (Swift MapSnapshotStore.resolutionStream).
    dependencies.mapSnapshotStore()?.let { store ->
        jobs += scope.launch {
            store.resolutions.collect { buildItems() }
        }
    }
    // Rebuild when an inline-image aspect is saved (Swift InlineImageDimensionsStore.resolutionStream).
    dependencies.inlineImageDimensions()?.let { store ->
        jobs += scope.launch {
            store.resolutions.collect { buildItems() }
        }
    }
    // Reload the row a reaction landed on, so its badge appears in the open thread rather than only
    // after a teardown and refetch (Swift .reactionReceived -> enqueueReload).
    dependencies.reactionService()?.let { reactions ->
        jobs += scope.launch {
            reactions.summaryUpdates.collect { writer?.enqueueReload(it.messageID) }
        }
    }
    eventJobs = jobs
}

/**
 * Reloads when this DM's messages were wiped from contact detail. The list already refreshes
 * via `notifyConversationsChanged`; the open thread would otherwise keep the old page.
 */
suspend fun ChatThreadViewModel.handleMessagesCleared(contactID: Uuid) {
    val key = conversationID.conversation
    if (key is ChatConversationID.ConversationKey.Dm && key.contactID == contactID) {
        loadInitial(ChatPopulateMode.REPLACE)
    }
}

/**
 * Reloads when a channel sender was blocked from this thread. The list already refreshes via
 * `notifyConversationsChanged`; the open channel would otherwise keep the deleted bubbles.
 */
suspend fun ChatThreadViewModel.handleChannelSenderBlocked() {
    val key = conversationID.conversation
    if (key is ChatConversationID.ConversationKey.Channel) {
        loadInitial(ChatPopulateMode.REPLACE)
    }
}

/**
 * Folds one ingest event: a message for this conversation is admitted in real time; others are dropped.
 * Mirrors the `directMessageReceived`/`channelMessageReceived` arms of the Swift `handle`.
 */
suspend fun ChatThreadViewModel.handleIncoming(event: IncomingMessageEvent) {
    when (event) {
        is IncomingMessageEvent.DirectMessageReceived -> {
            val key = conversationID.conversation
            if (key is ChatConversationID.ConversationKey.Dm && key.contactID == event.contactID) {
                admission.admit(event.message)
                markOpenThreadMentionSeen(event.message)
            }
        }

        is IncomingMessageEvent.ChannelMessageReceived -> {
            val key = conversationID.conversation
            if (key is ChatConversationID.ConversationKey.Channel &&
                key.channelIndex == event.channelIndex &&
                event.message.radioID == conversationID.radioID
            ) {
                admission.admit(event.message)
                markOpenThreadMentionSeen(event.message)
            }
        }
    }
}

/**
 * Folds one outbound-status event. `statusResolved` applies in place (crossfade, no DB read); resend /
 * retry / failed route to the coordinator's coalesced reload; a routing change on the bound contact
 * bumps a refresh signal. The `when` is exhaustive by design so a new event kind is a compile error,
 * never a silent skip (spec §9.2). Mirrors the status arms of the Swift `handle`.
 */
suspend fun ChatThreadViewModel.handleStatus(event: MessageStatusEvent) {
    when (event) {
        is MessageStatusEvent.StatusResolved -> {
            writer?.applyStatusUpdate(event.messageID, event.status, event.roundTripTime)
            publish()
        }

        is MessageStatusEvent.Resent -> {
            writer?.enqueueReload(event.messageID)
            dependencies.onConversationsChanged()
        }
        is MessageStatusEvent.Failed -> {
            writer?.enqueueReload(event.messageID)
            markFailedSendSeenIfCurrent(event.messageID)
            dependencies.onConversationsChanged()
        }
        is MessageStatusEvent.Retrying -> writer?.enqueueReload(event.messageID)
        is MessageStatusEvent.HeardRepeatRecorded -> writer?.enqueueReload(event.messageID)
        is MessageStatusEvent.MessagesRegionUpdated ->
            writer?.enqueueReload(event.messageIDs.toSet())

        is MessageStatusEvent.RoutingChanged -> {
            val key = conversationID.conversation
            if (key is ChatConversationID.ConversationKey.Dm && key.contactID == event.contactID) {
                contactRefreshSignal += 1
                publish()
            }
        }
    }
}

/**
 * Releases the active-conversation slot (ownership-checked by the sink) and detaches the render hooks.
 * Not suspending: the thread's own coroutine scope is already cancelled when its host disposes, so a
 * release launched on it would never run and the conversation would stay claimed — raising no banner
 * and bumping no unread — for as long as the app stays foregrounded. Mirrors
 * [one.meshcore.shared.room.ui.RoomConversationViewModel.dispose].
 */
fun ChatThreadViewModel.cleanup() {
    eventJobs.forEach { it.cancel() }
    eventJobs = emptyList()
    ownedJobs.close()
    linkPreviewController?.clear()
    coordinator.releaseWriter(this)
    writer = null
    dependencies.activeConversation.clearActiveAsync(conversationID)
}
