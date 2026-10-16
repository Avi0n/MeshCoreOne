package one.meshcore.shared.chat

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.text.MessageTextLinkifier
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runReportingException

/**
 * Batch-loads the last-message preview per conversation. For each conversation the last non-reaction
 * message is kept (an outgoing reaction row is skipped unless it failed). A contact's cache is evicted
 * only when it has no messages at all — a cleared DM (still listed via its last-message date) then shows
 * "No messages", while a contact whose recent rows are all filtered-out reactions keeps its prior
 * preview. A channel with no visible message evicts.
 */
internal suspend fun ConversationListViewModel.loadLastMessagePreviews(store: ConversationListStore, radioID: Uuid) {
    val current = listState.value
    val updates = mutableMapOf<Uuid, String?>()
    collectContactPreviews(store, current.contacts, updates)
    collectChannelPreviews(store, current.channels, updates)
    currentCoroutineContext().ensureActive()
    if (ownsReload(store, radioID) && updates.isNotEmpty()) applyPreviewUpdates(updates)
}

private suspend fun ConversationListViewModel.collectContactPreviews(
    store: ConversationListStore,
    contacts: List<ContactDto>,
    updates: MutableMap<Uuid, String?>,
) {
    if (contacts.isEmpty()) return
    // Per-contact fetch so a single bad id cannot drop the whole batch (and so map-key identity
    // for Uuid cannot silently miss rows when the batch map is built with a different instance path).
    for (contact in contacts) {
        val messages = runReportingException(onFailure = { _ -> null }) {
            store.fetchLastContactMessages(
                listOf(contact.id),
                ConversationListViewModel.CONTACT_PREVIEW_LIMIT,
            )[contact.id]
        }
        val last = messages?.lastOrNull { !isSkippedReaction(it) }
        when {
            last != null -> updates[contact.id] = renderedPreview(last.text)
            // No messages at all evicts; an all-reactions list keeps the prior preview (no update).
            messages.isNullOrEmpty() -> updates[contact.id] = null
        }
    }
}

private suspend fun ConversationListViewModel.collectChannelPreviews(
    store: ConversationListStore,
    channels: List<ChannelDto>,
    updates: MutableMap<Uuid, String?>,
) {
    if (channels.isEmpty()) return
    for (channel in channels) {
        val key = ChannelPreviewKey(channel.id, channel.radioID, channel.index)
        val messages = runReportingException(onFailure = { _ -> null }) {
            store.fetchLastChannelMessages(listOf(key), ConversationListViewModel.CHANNEL_PREVIEW_LIMIT)[channel.id]
        } ?: continue
        updates[channel.id] = messages.lastOrNull { !isSkippedReaction(it) }?.text?.let(::renderedPreview)
    }
}

/** Renders mention tokens (`@[name]` -> `@name`) for a plain-text list preview. */
private fun ConversationListViewModel.renderedPreview(rawText: String): String =
    MessageTextLinkifier.linkify(rawText, currentUserName = null).formatted.displayString

private fun ConversationListViewModel.applyPreviewUpdates(updates: Map<Uuid, String?>) = listState.update { state ->
    val previews = state.previews.toMutableMap()
    for ((id, text) in updates) {
        if (text == null) previews.remove(id) else previews[id] = text
    }
    state.copy(previews = previews)
}

/** True when a message drops out of the preview: an outgoing reaction that has not failed. */
private fun ConversationListViewModel.isSkippedReaction(message: MessageDto): Boolean {
    if (message.direction != MessageDirection.OUTGOING) return false
    if (message.status == MessageStatus.FAILED) return false
    return dependencies.isReaction(message)
}
