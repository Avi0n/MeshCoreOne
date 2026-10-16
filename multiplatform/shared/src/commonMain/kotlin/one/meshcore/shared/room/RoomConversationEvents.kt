package one.meshcore.shared.room

import one.meshcore.shared.model.MessageStatus

internal suspend fun RoomConversationController.handleStatusUpdated(event: RoomServerEvent.StatusUpdated) {
    if (messages.none { it.id == event.messageID }) return
    if (event.status == MessageStatus.FAILED) {
        // Mark seen before the list reload so the chats-list badge does not reappear
        // (iOS `roomMessageFailed` + `markFailedSendsSeen` then `notifyConversationsChanged`).
        val current = session
        val service = inputs.roomServerService()
        if (current != null && service != null) {
            service.markFailedSendsSeen(current.id)
            inputs.onViewed?.invoke(current.id)
        }
    }
    scheduleCoalescedReload()
}
