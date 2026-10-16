package one.meshcore.shared.chat

internal fun ChatThreadViewModel.landAtBottom() {
    scrollToken += 1
    scrollCommand = ScrollCommand.ToBottom(scrollToken)
    isAtBottom = true
    unreadWhileScrolledUp = 0
}

/**
 * First-snapshot decision for this open. The divider target comes from this session's bake, and
 * presents only once the flag-bearing row is among the current items. Mirrors iOS
 * `ChatTimeline.firstSnapshot`.
 */
internal fun ChatThreadViewModel.currentFirstSnapshot(): ChatInitialScrollPolicy.FirstSnapshotDecision {
    val items = coordinator.renderState.items
    val dividerRowOnScreen = dividerMessageID?.let { id ->
        items.firstOrNull { it.id == id }?.grouping?.showNewMessagesDivider == true
    } ?: false
    return ChatInitialScrollPolicy.firstSnapshotDecision(
        hasConsumed = anchorConsumed,
        unreadCount = openUnreadCount,
        initialLoadSettled = initialLoadSettled,
        dividerMessageID = dividerMessageID,
        dividerRowOnScreen = dividerRowOnScreen,
    )
}

/**
 * Issues the one-shot open scroll once the first snapshot is ready to present. A withheld snapshot
 * waits for the next item rebuild. A notification target already set [openScrollIssued] so it is not
 * overwritten by the divider. Mirrors iOS withholding until `dividerRowOnScreen`.
 */
internal fun ChatThreadViewModel.applyInitialScrollIfReady() {
    if (openScrollIssued) return
    when (val decision = currentFirstSnapshot()) {
        ChatInitialScrollPolicy.FirstSnapshotDecision.Withhold -> return
        is ChatInitialScrollPolicy.FirstSnapshotDecision.Present -> {
            openScrollIssued = true
            val target = decision.target
            if (target != null) {
                scrollToken += 1
                scrollCommand = ScrollCommand.ToMessage(target, scrollToken, animated = false)
                isAtBottom = false
            }
        }
    }
}

/**
 * Marks the one-shot open positioning as spent. Called when the view's list has positioned on the
 * anchor; later snapshots present freely. Mirrors iOS `ChatTimeline.consumeAnchor`.
 */
fun ChatThreadViewModel.consumeAnchor() {
    if (anchorConsumed) return
    anchorConsumed = true
    publish()
}

internal fun ChatThreadViewModel.isCurrentLoad(generation: ULong): Boolean =
    generation == loadGeneration && writer?.isCurrent == true

internal fun ChatThreadViewModel.stageInitialOpen(): Boolean {
    val firstOpen = !didStageOpen || !initialLoadSettled
    if (firstOpen) {
        didStageOpen = true
        anchorConsumed = false
        initialLoadSettled = false
        openScrollIssued = false
        dividerMessageID = null
        openUnreadCount = dependencies.unreadCount()
    }
    return firstOpen
}

internal fun ChatThreadViewModel.settleInitialLoad(generation: ULong, firstOpen: Boolean, didComplete: Boolean) {
    if (!isCurrentLoad(generation)) return
    // Cancellation leaves staging available so a retry can build this open's unread divider.
    if (firstOpen && didComplete) initialLoadSettled = true
    writer?.markLoaded()
    if (didComplete) applyInitialScrollIfReady()
    publish()
}
