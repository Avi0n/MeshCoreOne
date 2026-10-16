package one.meshcore.shared.chat

import kotlinx.coroutines.launch
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.reactions.ReactionParser
import one.meshcore.shared.model.Uuid

/**
 * The single chokepoint for ack / retry / fail / heard-repeat / reaction events. Unions the ids into
 * [pendingReloadIDs] and schedules one coalesced drain; no event is dropped because none asks whether a
 * row is currently rendered.
 */
fun ChatCoordinator.enqueueReload(updatedMessageIDs: Set<Uuid>) {
    pendingReloadIDs.addAll(updatedMessageIDs)
    scheduleCoalescedReload()
}

/** Convenience for the single-id hot path. */
fun ChatCoordinator.enqueueReload(messageID: Uuid) = enqueueReload(setOf(messageID))

private fun ChatCoordinator.scheduleCoalescedReload() {
    if (reloadInFlight || hardResetInFlight) return
    reloadInFlight = true
    coalescedReloadLaunches += 1
    coalescedReloadJob = scope.launch { coalescedReload() }
}

/**
 * Drain [pendingReloadIDs] until empty: snapshot, clear, fetch, apply. Re-checks at the loop top so
 * events landing during a fetch reconcile in the next pass rather than starting a new drain. The
 * [hardResetInFlight] break stops a mid-flight drain from stomping freshly reset state.
 */
private suspend fun ChatCoordinator.coalescedReload() {
    while (pendingReloadIDs.isNotEmpty()) {
        if (hardResetInFlight) break
        val snapshot = pendingReloadIDs.toSet()
        pendingReloadIDs.clear()
        applyReloadedIDs(snapshot)
    }
    reloadInFlight = false
}

/**
 * Per-id fetch + in-place update. A fetch returning null for an id the coordinator still holds is an
 * inconsistency and triggers [hardReset]. After the successful refreshes, [renderItemRebuilder] rebuilds
 * each affected item so events refresh the bubble without a full timeline rebuild.
 */
private suspend fun ChatCoordinator.applyReloadedIDs(ids: Set<Uuid>) {
    var inconsistencyDetected = false
    val refreshedIDs = mutableListOf<Uuid>()
    for (id in ids) {
        val fetched = store.fetchMessage(id)
        if (fetched != null) {
            if (!messagesByID.containsKey(id)) continue
            update(id) { fetched }
            refreshedIDs.add(id)
        } else if (messagesByID.containsKey(id)) {
            inconsistencyDetected = true
        }
    }
    renderItemRebuilder?.let { rebuild -> refreshedIDs.forEach(rebuild) }
    if (inconsistencyDetected) hardReset()
}

/**
 * Fail-safe: drop per-id reconciliation and refetch the already-loaded window from
 * `min(sortDate)`. Invoked from [applyReloadedIDs] inside the in-flight drain;
 * [hardResetInFlight] plus the drain's loop-top break ensure that drain exits without
 * stomping the refetched state, and any ids buffered during the reset drain afterward.
 * Mirrors iOS `ChatCoordinator.hardReset`.
 */
private fun ChatCoordinator.hardReset() {
    hardResetInFlight = true
    hardResetJob = scope.launch {
        try {
            performWindowOperation {
                val anchorSortDate = messages.minOfOrNull { it.sortDate }
                val window = store.fetchMessageWindow(conversationID, anchorSortDate, ChatCoordinator.PAGE_SIZE)
                val unfilteredCount = window.messages.size
                replaceAll(hidingOutgoingReactions(window.messages))
                updateRenderState {
                    it.copy(
                        hasMoreMessages = window.hasMore,
                        totalFetchedCount = unfilteredCount,
                    )
                }
                renderStateInvalidated?.invoke()
            }
        } finally {
            hardResetInFlight = false
            if (pendingReloadIDs.isNotEmpty()) scheduleCoalescedReload()
        }
    }
}

private fun ChatCoordinator.hidingOutgoingReactions(messages: List<MessageDto>): List<MessageDto> {
    val isDM = conversationID.conversation is ChatConversationID.ConversationKey.Dm
    return messages.filterNot { message ->
        isHiddenOutgoingReaction(message) { ReactionParser.isSentReactionText(it.text, isDM) }
    }
}
