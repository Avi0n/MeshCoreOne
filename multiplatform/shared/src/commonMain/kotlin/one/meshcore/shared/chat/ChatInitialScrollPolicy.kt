package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/**
 * Pure decisions governing the chat list's first snapshot when a conversation opens: whether to hand
 * the list its first items yet, and which item to open scrolled to (the "New Messages" divider).
 *
 * The list's one-shot initial positioning is spent by the first non-empty snapshot it receives, so the
 * timeline must stay withheld until the snapshot on screen is the one it should position on.
 */
object ChatInitialScrollPolicy {
    /** Whether the timeline is ready to show and, if so, the divider id it opens scrolled to. */
    sealed interface FirstSnapshotDecision {
        /**
         * Keep the timeline off screen; a divider target is expected but its flag-bearing item is
         * not yet among the current items.
         */
        data object Withhold : FirstSnapshotDecision

        /** Show the timeline, opening scrolled to [target] (null opens at the bottom). */
        data class Present(val target: Uuid?) : FirstSnapshotDecision
    }

    /**
     * Decides the first snapshot for a conversation open. The divider target comes from the per-session
     * bake, never from state a shared coordinator carries over from a previous open, and presents only once
     * the item baked with the divider row is among the items currently on screen — a stale warm page
     * therefore withholds. [dividerRowOnScreen] is that readiness fact, computed by the caller from the
     * current items. [initialLoadSettled] is the escape hatch: a populate that finished (any outcome)
     * without a divider target has nothing to wait for.
     */
    fun firstSnapshotDecision(
        hasConsumed: Boolean,
        unreadCount: Int,
        initialLoadSettled: Boolean,
        dividerMessageID: Uuid?,
        dividerRowOnScreen: Boolean,
    ): FirstSnapshotDecision {
        if (hasConsumed || unreadCount <= 0) return FirstSnapshotDecision.Present(null)
        val divider = dividerMessageID
        if (divider != null) {
            // Present only when the flag-bearing row is on screen: id membership alone can be a prior
            // bake without the flag, and presenting that spends the one-shot before a later rebake grows a
            // row under the input bar.
            return if (dividerRowOnScreen) {
                FirstSnapshotDecision.Present(divider)
            } else {
                FirstSnapshotDecision.Withhold
            }
        }
        return if (initialLoadSettled) {
            FirstSnapshotDecision.Present(null)
        } else {
            FirstSnapshotDecision.Withhold
        }
    }
}
