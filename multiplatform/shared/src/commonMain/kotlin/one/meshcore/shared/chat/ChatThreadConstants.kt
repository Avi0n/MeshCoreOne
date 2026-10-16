package one.meshcore.shared.chat

/**
 * Named constants for the conversation thread view model and its Compose surface, aligned with iOS
 * §9. The iOS scroll timings (§9.5) are a flipped-`UITableView` tuning and do not transfer literally; the
 * Android values below are the `LazyColumn(reverseLayout = true)` equivalents, chosen so the *behaviour*
 * the spec fixes (near-bottom auto-scroll gate, near-top pagination trigger, positional divider) matches.
 */
object ChatThreadConstants {
    /**
     * Unread count above which the positional "new messages" divider is drawn (spec §9.4, strictly greater).
     * Zero draws the divider for any unread backlog; when the unread rows fit on one screen the open
     * position clamps to the bottom with the divider line visible.
     */
    const val NEW_MESSAGES_DIVIDER_THRESHOLD: Int = 0

    /**
     * How close (in logical items) the visual top must come before the next older page is requested. Under
     * `reverseLayout` the logical end is the visual top, so the trigger watches the last visible index
     * approaching the list size. Mirrors the iOS `nearTopTriggerDistance` of 10 rows (spec §9.5).
     */
    const val NEAR_TOP_PREFETCH_DISTANCE: Int = 10

    /**
     * First-visible index at or below which the timeline counts as "near the bottom" for the auto-scroll
     * gate: a live arrival auto-scrolls only inside this band, otherwise it raises the scroll-to-bottom
     * affordance instead of yanking the viewport (spec §9.5, the Meshtastic-Android pattern the plan
     * mandates). Item 0 is the newest row (visual bottom) under `reverseLayout`.
     */
    const val NEAR_BOTTOM_ITEM_THRESHOLD: Int = 2
}
