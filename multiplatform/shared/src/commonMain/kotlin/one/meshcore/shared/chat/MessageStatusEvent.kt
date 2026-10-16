package one.meshcore.shared.chat

import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/**
 * Outbound-message lifecycle notifications broadcast by [MessageService]. Subscribe via
 * [MessageService.statusEvents]. Events carry the resolved messageID rather than the raw ACK code so
 * consumers gate on conversation membership without re-walking the pending-ACK table. Mirrors the Swift
 * `MessageStatusEvent`.
 */
sealed interface MessageStatusEvent {
    /**
     * A message reached a resolved status: `.sent` once the radio queues a DM or channel broadcast,
     * `.delivered` once a DM's end-to-end ACK arrives. Channel broadcasts have no ACK, so `.sent` is
     * their terminal success. [roundTripTime] is the firmware-reported value when supplied.
     */
    data class StatusResolved(val messageID: Uuid, val status: MessageStatus, val roundTripTime: Long? = null) :
        MessageStatusEvent

    /**
     * A resend completed with `.sent` committed. Carries no status payload because resends also mutate
     * `heardRepeats`/`sendCount`; consumers refresh the whole DTO rather than apply a status-only update.
     */
    data class Resent(val messageID: Uuid) : MessageStatusEvent

    /** A retry attempt is in flight; drives UI retry progress. */
    data class Retrying(val messageID: Uuid, val attempt: Int, val maxAttempts: Int) : MessageStatusEvent

    /** A contact's routing switched between direct and flood during a send. */
    data class RoutingChanged(val contactID: Uuid, val isFlood: Boolean) : MessageStatusEvent

    /** A message failed after exhausting retries or a terminal send error. */
    data class Failed(val messageID: Uuid) : MessageStatusEvent

    /**
     * A channel broadcast's heard-repeats counter increased after an RX-log echo matched our own send
     * (iOS `MessageEvent.heardRepeatRecorded`). Consumers reload the row so the bubble footer updates.
     */
    data class HeardRepeatRecorded(val messageID: Uuid, val count: Int) : MessageStatusEvent

    /**
     * Dual region or hop-path fields were rewritten after a late RX-log attribution (iOS
     * `messagesRegionUpdated`). Open threads reload those rows so region chips, hop footers, and
     * Path Map appear without a refetch of the whole page.
     */
    data class MessagesRegionUpdated(val messageIDs: List<Uuid>) : MessageStatusEvent
}

/**
 * Whether the chats list should requery failed-send badges. Mirrors iOS
 * `ChatViewModel.shouldRefreshFailedSendIndicators`: a fail or resend always dirties the list;
 * a status-resolved ACK only requeries when a badge is already showing, so a retry success can
 * drop it without hitting the store on every ACK.
 */
fun MessageStatusEvent.shouldRefreshFailedSendIndicators(hasFailedSendBadges: Boolean = false): Boolean =
    this is MessageStatusEvent.Failed ||
        this is MessageStatusEvent.Resent ||
        (this is MessageStatusEvent.StatusResolved && hasFailedSendBadges)
