package one.meshcore.shared.notification

import one.meshcore.shared.model.NotificationLevel

/**
 * The pure per-message decision of whether an incoming message warrants a user notification, mirroring
 * the posting guards in Swift `NotificationService.postDirectMessageNotification` /
 * `postChannelMessageNotification` plus the active-conversation suppression iOS applies in `willPresent`.
 * The environment gates the platform owns (OS authorization, the preference toggles, the sync-window
 * suppression flag) are layered on top by [NotificationCoordinator]; this object is only the
 * conversation-level truth table the shouldPost tests pin.
 *
 * Active-conversation suppression is folded in here rather than deferred to a foreground-presentation
 * hook: Android has no `willPresent` equivalent, so a message for the conversation on screen is simply
 * not posted, matching the iOS intent of never alerting for what the user is already reading.
 */
object NotificationGate {
    /**
     * Whether a direct message posts. A DM uses a boolean mute (mapped to muted/all): a muted contact or
     * the conversation the user is currently viewing suppresses it.
     */
    fun shouldPostDirect(isMuted: Boolean, isActiveConversation: Boolean): Boolean = !isMuted && !isActiveConversation

    /**
     * Whether a channel message posts. [channelResolved] is the phantom-channel firmware gate (§13.3): a
     * message on a slot with no local channel has no openable chat and must never surface. Otherwise the
     * per-conversation [level] governs — muted never posts, mentionsOnly posts only on a self-mention, all
     * posts every message — and the actively-viewed channel is suppressed like a DM.
     */
    fun shouldPostChannel(
        level: NotificationLevel,
        hasSelfMention: Boolean,
        isActiveConversation: Boolean,
        channelResolved: Boolean,
    ): Boolean {
        if (!channelResolved) return false
        if (isActiveConversation) return false
        return when (level) {
            NotificationLevel.MUTED -> false
            NotificationLevel.MENTIONS_ONLY -> hasSelfMention
            NotificationLevel.ALL -> true
        }
    }

    /**
     * Whether a reaction posts. Mute/self/outgoing gates run upstream; here only the open-thread
     * suppression iOS applies in `willPresent` remains.
     */
    fun shouldPostReaction(isActiveConversation: Boolean): Boolean = !isActiveConversation
}
