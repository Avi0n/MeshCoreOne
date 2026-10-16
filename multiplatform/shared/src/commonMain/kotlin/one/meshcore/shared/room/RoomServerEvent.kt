package one.meshcore.shared.room

import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/**
 * Room-server notifications broadcast by [RoomServerService.events]. The stream is multicast: every
 * subscriber sees every event. Mirrors the Swift `RoomServerEvent`. Consumers re-subscribe per connection
 * because the owning service is rebuilt on every connection.
 */
sealed interface RoomServerEvent {
    /** An outbound room message's delivery status changed. */
    data class StatusUpdated(val messageID: Uuid, val status: MessageStatus) : RoomServerEvent

    /** An incoming message recovered a disconnected room session. */
    data class ConnectionRecovered(val sessionID: Uuid) : RoomServerEvent

    /**
     * A new (non-duplicate) room message was ingested. The Swift port carries this on the message event
     * stream as `roomMessageReceived`; here it rides the room stream so one subscription feeds an open
     * conversation.
     */
    data class MessageReceived(val message: RoomMessageDto, val sessionID: Uuid) : RoomServerEvent
}

/**
 * Whether the chats list should requery failed-send badges for a room-server event. Mirrors iOS
 * `roomMessageFailed` (always) and `roomMessageStatusUpdated` (only when a badge is already showing).
 */
fun RoomServerEvent.shouldRefreshFailedSendIndicators(hasFailedSendBadges: Boolean): Boolean = when (this) {
    is RoomServerEvent.StatusUpdated ->
        status == MessageStatus.FAILED || hasFailedSendBadges
    else -> false
}
