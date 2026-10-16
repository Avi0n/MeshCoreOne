package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.channel.ChannelService
import one.meshcore.shared.model.Uuid

/**
 * Deletes a conversation. Direct conversations clear via a local write, so the row hides optimistically
 * and restores if the write throws. Channel and room deletes send a radio command: the row keeps a
 * spinner until the command acks, then hides once; a failure leaves it in place with an error. Every
 * path ends by reloading so the buffers reconcile.
 */
suspend fun ConversationListViewModel.deleteConversation(conversation: Conversation): Boolean = when (conversation) {
    is Conversation.Direct -> deleteDirect(conversation)
    is Conversation.Channel, is Conversation.Room -> deleteViaRadio(conversation)
}

@Suppress("TooGenericExceptionCaught")
private suspend fun ConversationListViewModel.deleteDirect(conversation: Conversation.Direct): Boolean {
    val store = storeOrReportDisconnected() ?: return false
    if (listState.value.isDeletePending(conversation.id)) return false
    removeConversation(conversation)
    try {
        store.clearDirectConversation(conversation.id)
        confirmDirectRemoval(conversation.id)
        dependencies.updateBadge()
        reload()
        return true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        restoreConversation(conversation)
        listState.update { it.copy(errorMessage = messages.userFacing(error, messages.deleteConversationFailed)) }
        return false
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun ConversationListViewModel.deleteViaRadio(conversation: Conversation): Boolean {
    val store = storeOrReportDisconnected() ?: return false
    if (listState.value.isDeletePending(conversation.id)) return false
    val radioAction = radioDeleteAction(conversation, store) ?: return false
    listState.update { it.copy(deletingIds = it.deletingIds + conversation.id) }
    var failure: String? = null
    var removed = false
    try {
        // A radio that never answers must not leave the row spinning forever; the row stays visible
        // with a retryable error. Only this operation's own deadline becomes that result.
        val completed = withTimeoutOrNull(ConversationListViewModel.RADIO_DELETE_TIMEOUT_MS) {
            radioAction()
            true
        } ?: false
        if (completed) {
            removeConversation(conversation)
            removed = true
        } else {
            failure = messages.deleteTimedOut
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        failure = messages.userFacing(error, messages.deleteConversationFailed)
    } finally {
        listState.update { it.copy(deletingIds = it.deletingIds - conversation.id) }
    }
    reload()
    // Published after the reconciling reload, which clears the error field on entry; setting it first
    // would leave a failed radio delete silent while the row is back in the list.
    failure?.let { message ->
        listState.update { current ->
            when (conversation) {
                is Conversation.Channel -> current.copy(
                    channelDeleteFailure = ChannelDeleteFailure(conversation.channel, message),
                )
                else -> current.copy(errorMessage = message)
            }
        }
    }
    return removed
}

/**
 * The live store when the radio is Ready, or null having already told the user the delete needs a
 * connected radio. iOS `deleteDirectConversation` throws `.notConnected` unless
 * `connectionState == .ready`; the offline store must not wipe a DM while disconnected.
 */
private fun ConversationListViewModel.storeOrReportDisconnected(): ConversationListStore? {
    val store = dependencies.store()
    if (store == null || !dependencies.isReady()) {
        listState.update { it.copy(errorMessage = messages.notConnectedToDelete) }
        return null
    }
    return store
}

/**
 * What the radio has to do before the row goes, resolved before it starts spinning. Null when the
 * delete was never attempted, having already published the reason: a gone service has to be named in
 * the user's language rather than reported as a generic failure.
 */
private fun ConversationListViewModel.radioDeleteAction(
    conversation: Conversation,
    store: ConversationListStore,
): (suspend () -> Unit)? = when (conversation) {
    is Conversation.Channel -> dependencies.channelService()?.let { service ->
        suspend { clearChannelOnRadio(service, conversation.channel) }
    } ?: run {
        listState.update { it.copy(errorMessage = messages.servicesUnavailable) }
        null
    }
    is Conversation.Room -> suspend { store.leaveRoom(conversation.session) }
    is Conversation.Direct -> suspend { }
}

/**
 * Vacates the channel's slot on the radio, then deletes its messages, local row and drafts, refreshes
 * the RX decrypt cache, and clears its delivered notifications. Mirrors the Swift
 * `ChatConversationActions.deleteChannel`; [ChannelService.clearChannel] owns the whole cascade.
 */
private suspend fun ConversationListViewModel.clearChannelOnRadio(service: ChannelService, channel: ChannelDto) {
    service.clearChannel(channel.radioID, channel.index)
    dependencies.clearChannelNotifications(channel.index, channel.radioID)
}

/**
 * Hides a conversation: records the id in [ConversationListState.pendingRemovalIds] so a racing reload
 * can't resurrect it, drops it from the buffer, and recomputes. [reconciledPendingRemovals] later drops
 * the id once a fetch confirms the row gone.
 */
internal fun ConversationListViewModel.removeConversation(conversation: Conversation) = listState.update { current ->
    current.copy(
        pendingRemovalIds = current.pendingRemovalIds + conversation.id,
        contacts = current.contacts.filterNot { it.id == conversation.id },
        channels = current.channels.filterNot { it.id == conversation.id },
        rooms = current.rooms.filterNot { it.id == conversation.id },
    ).recomputed()
}

/**
 * Re-admits a row after its delete failed: drops the mask and re-inserts the caller-held DTO. Reusing
 * the held DTO rather than refetching keeps the rollback independent of reload timing.
 */
internal fun ConversationListViewModel.restoreConversation(conversation: Conversation) = listState.update { current ->
    val restored = when (conversation) {
        is Conversation.Direct ->
            if (current.contacts.none { it.id == conversation.id }) {
                current.copy(contacts = current.contacts + conversation.contact)
            } else {
                current
            }
        is Conversation.Channel ->
            if (current.channels.none { it.id == conversation.id }) {
                current.copy(channels = current.channels + conversation.channel)
            } else {
                current
            }
        is Conversation.Room ->
            if (current.rooms.none { it.id == conversation.id }) {
                current.copy(rooms = current.rooms + conversation.session)
            } else {
                current
            }
    }
    restored.copy(pendingRemovalIds = restored.pendingRemovalIds - conversation.id).recomputed()
}

/**
 * Confirms a direct conversation's local clear: drops the mask and purges the buffer together, so a
 * stale reload that re-added the contact while it was masked can't republish it. A re-created row still
 * returns via the next reload's fetch.
 */
internal fun ConversationListViewModel.confirmDirectRemoval(contactID: Uuid) = listState.update { current ->
    current.copy(
        pendingRemovalIds = current.pendingRemovalIds - contactID,
        contacts = current.contacts.filterNot { it.id == contactID },
    ).recomputed()
}
