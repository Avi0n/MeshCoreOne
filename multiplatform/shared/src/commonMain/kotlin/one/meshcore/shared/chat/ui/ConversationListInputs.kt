package one.meshcore.shared.chat.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.channel.ChannelService
import one.meshcore.shared.chat.Conversation
import one.meshcore.shared.chat.ConversationListStore
import one.meshcore.shared.chat.MessageStatusEvent
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.room.RoomServerEvent

/**
 * Values the Chats tab reads. The chat UI does not take the shell bundle.
 */
internal data class ConversationListInputs(
    val store: () -> ConversationListStore?,
    val contactService: () -> ContactService?,
    val radioId: () -> Uuid?,
    val connectionState: StateFlow<DeviceConnectionState>,
    val onConversationTap: (Conversation) -> Unit,
    val onStartNewChat: (ContactDto) -> Unit = { contact -> onConversationTap(Conversation.Direct(contact)) },
    val selectedRoute: ChatRoute? = null,
    val onNewChannel: () -> Unit = {},
    val onConversationDeleted: (Conversation) -> Unit = {},
    /**
     * Called after a snapshot or retained-contact change so the host can reconcile its open chat.
     */
    val onSnapshotRefreshed: (List<Conversation>, Set<Uuid>?) -> Unit = { _, _ -> },
    /** Ingest/block/delete invalidations; reloads the list without waiting for a connection flip. */
    val listInvalidations: Flow<Unit> = emptyFlow(),
    /**
     * The live channel service; the chat-list channel delete vacates the radio slot through it. Null until
     * the shell wires it, and a chat-list channel delete then reports that services are unavailable rather
     * than deleting local rows behind a still-configured radio slot.
     */
    val channelService: () -> ChannelService? = { null },
    /** Clears a deleted channel's delivered notifications and refreshes the badge. */
    val clearChannelNotifications: suspend (channelIndex: Int, radioID: Uuid) -> Unit = { _, _ -> },
    /** Refreshes the OS unread badge after mute or a DM delete. */
    val updateBadge: suspend () -> Unit = {},
    /**
     * Warms inline-image and link-preview dimensions for chats on the list. Tap uses
     * [prefetchConversation]. Default no-op.
     */
    val prewarmConversationPreviews: suspend (List<Conversation>) -> Unit = {},
    /**
     * Speculatively loads page 0 of a closed chat before navigation. Default no-op.
     */
    val prefetchConversation: suspend (Conversation) -> Unit = {},
    /**
     * Outbound DM/channel status events. A fail/resend always requeries failed-send badges; a
     * status-resolved ACK only does when a badge is already showing.
     */
    val statusEvents: () -> Flow<MessageStatusEvent> = { emptyFlow() },
    /** Room-server status events, the room counterpart of [statusEvents]. */
    val roomEvents: () -> Flow<RoomServerEvent> = { emptyFlow() },
)
