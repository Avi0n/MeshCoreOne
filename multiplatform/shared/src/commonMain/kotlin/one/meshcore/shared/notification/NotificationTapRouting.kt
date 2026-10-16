package one.meshcore.shared.notification

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.model.Uuid

/**
 * Finds the stored channel a channel-notification tap should open. Looks up [channelIndex] on the
 * notification's radio, not the radio that happens to be connected now, matching iOS
 * `fetchChannel(radioID:index:)`.
 */
fun channelRowIdForNotification(channelsOnNotificationRadio: List<ChannelDto>, channelIndex: Int): Uuid? =
    channelsOnNotificationRadio.firstOrNull { it.index == channelIndex }?.id

/**
 * The chat-store conversation shade Mark as Read should zero. A DM is keyed by contact id (iOS
 * `clearUnreadCount(contactID:)`); [contactRadioId] is that row's partition so Room can form a
 * [ChatConversationID] without the live or last radio. A channel already carries its radio on the
 * notification key. A room is not a chat-store row.
 */
fun markReadChatConversation(target: ConversationTarget, contactRadioId: Uuid?): ChatConversationID? = when (target) {
    is ConversationTarget.Direct ->
        contactRadioId?.let { ChatConversationID.dm(it, target.contactID) }
    is ConversationTarget.Channel ->
        ChatConversationID.channel(target.radioID, target.channelIndex)
    is ConversationTarget.Room -> null
}
