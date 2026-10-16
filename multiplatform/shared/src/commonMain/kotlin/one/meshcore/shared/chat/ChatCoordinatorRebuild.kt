package one.meshcore.shared.chat

import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/**
 * The single seam off-thread builds apply through. Returns false when [capturedID] no longer matches
 * [renderStateID] — the build is stale and the caller must re-run.
 */
fun ChatCoordinator.setRenderState(new: ChatRenderState, capturedID: ULong): Boolean {
    if (capturedID != renderStateID) return false
    val previous = renderState
    renderState = new
    resumeScrollRenderWaiterIfHonorable(previous, new)
    return true
}

/**
 * Direct render-state assignment for pagination fields and single-row updates that already keep
 * the canonical messages array consistent. Bumps [renderStateID] because the new snapshot may
 * displace an in-flight off-thread build. Mirrors iOS `ChatCoordinator.updateRenderState`.
 */
fun ChatCoordinator.updateRenderState(transform: (ChatRenderState) -> ChatRenderState) {
    val previous = renderState
    renderState = transform(previous)
    bumpRenderStateID()
    resumeScrollRenderWaiterIfHonorable(previous, renderState)
}

/** Append a fully built item so a new bubble shows immediately without waiting for the next build. */
fun ChatCoordinator.appendRenderItem(item: MessageItem) {
    updateRenderState { it.appendingItem(item) }
}

/** Replace a single render item by id. No-op on a missing id or an equal result. */
fun ChatCoordinator.updateRenderItem(id: Uuid, transform: (MessageItem) -> MessageItem) {
    val newState = renderState.updatingItem(id, transform)
    if (newState == renderState) return
    renderState = newState
    bumpRenderStateID()
}

/** Remove a single render item by id. */
fun ChatCoordinator.removeRenderItem(id: Uuid) {
    val newState = renderState.removingItem(id)
    if (newState == renderState) return
    renderState = newState
    bumpRenderStateID()
}

/**
 * Apply a status-only update in place: mutate the canonical DTO and rebuild the rendered item's envelope
 * and footer status, with no DB read. [roundTripTime] is preserved when null so a `.sent` transition
 * doesn't clobber an RTT a prior `.delivered` recorded. Guards delegate to [MessageStatusGuards]: a
 * non-user-initiated write can't downgrade a delivered row nor flip a failed row back to pending.
 */
fun ChatCoordinator.applyStatusUpdate(
    messageID: Uuid,
    status: MessageStatus,
    roundTripTime: Long? = null,
    userInitiated: Boolean = false,
) {
    val current = messagesByID[messageID]
    if (current != null && !MessageStatusGuards.allowsInPlaceUpdate(current.status, status, userInitiated)) {
        return
    }
    update(messageID) { it.copy(status = status, roundTripTime = roundTripTime ?: it.roundTripTime) }
    updateRenderItem(messageID) { item ->
        item.copy(
            envelope = item.envelope.copy(status = status),
            footer = item.footer.copy(status = status),
        )
    }
}

/** Flips a message's mention-seen flag in place once its bubble scrolls into view. No DB read. */
fun ChatCoordinator.applyMentionSeen(messageID: Uuid) {
    update(messageID) { it.copy(mentionSeen = true) }
    updateRenderItem(messageID) { item -> item.copy(envelope = item.envelope.copy(mentionSeen = true)) }
}

// region Off-thread item build

/**
 * Rebuild [renderState] from inputs the caller assembled on the confinement dispatcher. Bumps and
 * captures [renderStateID], runs the pure per-message build on [computeDispatcher], then applies on the
 * confinement dispatcher only if the counter still matches. A stale result fires [renderStateInvalidated]
 * so the caller reassembles and retries. [postApply] runs after a successful apply.
 */
fun ChatCoordinator.rebuildItems(
    inputs: List<Pair<MessageDto, MessageBuildInputs>>,
    envInputs: EnvInputs,
    postApply: (() -> Unit)? = null,
) {
    // Bump before capturing so two back-to-back rebuilds can't share a capturedID; last scheduled wins.
    bumpRenderStateID()
    val capturedID = renderStateID
    val snapshot = inputs

    buildItemsJob?.cancel()
    buildItemsJob = scope.launch {
        val built = withContext(computeDispatcher) {
            snapshot.map { (message, perMessageInputs) ->
                MessageFragmentBuilder.makeItem(message, perMessageInputs, envInputs)
            }
        }
        if (!isActive) return@launch
        applyRebuiltItems(built, capturedID, postApply)
    }
}

private fun ChatCoordinator.applyRebuiltItems(built: List<MessageItem>, capturedID: ULong, postApply: (() -> Unit)?) {
    val new = renderState.copy(items = built, itemIndexByID = built.indexByID())
    if (!setRenderState(new, capturedID)) {
        renderStateInvalidated?.invoke()
        return
    }
    postApply?.invoke()
}
