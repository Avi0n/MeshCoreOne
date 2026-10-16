package one.meshcore.shared.channel

/**
 * The branch a from-message join sheet renders while resolving whether it can join, shared by the channel-
 * link and hashtag confirmation flows. Mirrors the iOS three-state gate (`isLoading` → `isMissingDevice` →
 * `availableSlots.isEmpty` → confirmation) in `JoinChannelConfirmationSheet` / `JoinHashtagFromMessageView`.
 */
enum class ChannelJoinPhase {
    /** Slots are still loading. */
    LOADING,

    /** No radio is connected, so nothing can be joined. */
    MISSING_DEVICE,

    /** A radio is connected but every user slot is occupied. */
    NO_SLOTS,

    /** A free slot exists; the confirmation is shown. */
    READY,
}

/**
 * Confirmation state for a from-message join sheet. [successTick] fires the success haptic.
 * [error] is the failed join, including no slots and a nil read-back.
 */
data class ChannelJoinSheetState(
    val phase: ChannelJoinPhase = ChannelJoinPhase.LOADING,
    val isJoining: Boolean = false,
    val error: ChannelActionError? = null,
    val successTick: Int = 0,
)
