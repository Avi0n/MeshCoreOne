package one.meshcore.shared.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.chat.ActiveConversationSink
import one.meshcore.shared.chat.ChatConversationID

/**
 * The production [ActiveConversationSink]. Claiming routes through [NotificationCoordinator] so opening a
 * thread also clears the tray rows it already delivered and recomputes the badge, the pair Swift runs in
 * `ChatConversationView.clearDeliveredNotifications`.
 *
 * The release runs on [scope] rather than the caller's, because the thread's own coroutine scope is
 * already cancelled when its host disposes; it is ownership-checked, so a teardown landing after a newer
 * conversation claimed the slot cannot un-suppress the conversation on screen. The room counterpart is
 * [one.meshcore.shared.room.RoomNotificationSink.clearActiveRoomAsync].
 */
class NotificationActiveConversationSink(
    private val coordinator: NotificationCoordinator,
    private val scope: CoroutineScope,
) : ActiveConversationSink {
    override suspend fun setActive(conversation: ChatConversationID) {
        when (val key = conversation.conversation) {
            is ChatConversationID.ConversationKey.Dm -> coordinator.setActiveContact(key.contactID)
            is ChatConversationID.ConversationKey.Channel ->
                coordinator.setActiveChannel(conversation.radioID, key.channelIndex)
        }
    }

    override fun clearActiveAsync(conversation: ChatConversationID) {
        scope.launch {
            when (val key = conversation.conversation) {
                is ChatConversationID.ConversationKey.Dm -> coordinator.clearActiveContact(key.contactID)
                is ChatConversationID.ConversationKey.Channel ->
                    coordinator.clearActiveChannel(conversation.radioID, key.channelIndex)
            }
        }
    }
}
