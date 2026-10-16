package one.meshcore.shared.chat.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ActiveConversationSink
import one.meshcore.shared.chat.ChatCoordinatorRegistry
import one.meshcore.shared.chat.ChatDraftEditors
import one.meshcore.shared.chat.ChatSendQueueService
import one.meshcore.shared.chat.ChatThreadStore
import one.meshcore.shared.chat.IncomingMessageEvent
import one.meshcore.shared.chat.MessageService
import one.meshcore.shared.chat.MessageStatusEvent
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.model.Uuid

interface ChatThreadGraphHost {
    val registry: () -> ChatCoordinatorRegistry?

    val store: () -> ChatThreadStore?

    val sendQueue: () -> ChatSendQueueService?

    val messageService: () -> MessageService?

    val draftEditors: () -> ChatDraftEditors?

    /**
     * A scope on the editor UI dispatcher outliving the composition, used for teardown. Required, with no
     * do-nothing default, so a host that drops the wiring fails to compile rather than silently losing
     * every draft typed inside the debounce window.
     */
    val teardownScope: () -> CoroutineScope

    val currentContact: () -> ContactDto?

    val unreadCount: () -> Int

    val localNodeName: () -> String?

    /** Discovered repeaters for message-path hop resolution (iOS `discoveredRepeaters`). */
    val fetchDiscoveredRepeaters: suspend (Uuid) -> List<DiscoveredNodeRecord>

    /**
     * Live node name. When present the open thread collects it so a Device Info rename
     * recomputes the channel composer byte budget without leaving the conversation.
     */
    val localNodeNameState: StateFlow<String?>?

    val incomingEvents: suspend () -> Flow<IncomingMessageEvent>

    val statusEvents: suspend () -> Flow<MessageStatusEvent>

    val activeConversation: ActiveConversationSink
}

/** Registry, send path, and the live feeds the open thread reads at each use. */
data class ChatThreadGraphInputs(
    override val registry: () -> ChatCoordinatorRegistry?,
    override val store: () -> ChatThreadStore?,
    override val sendQueue: () -> ChatSendQueueService?,
    override val messageService: () -> MessageService?,
    override val draftEditors: () -> ChatDraftEditors?,
    override val teardownScope: () -> CoroutineScope,
    override val currentContact: () -> ContactDto?,
    override val unreadCount: () -> Int,
    override val localNodeName: () -> String?,
    override val fetchDiscoveredRepeaters: suspend (Uuid) -> List<DiscoveredNodeRecord> = { emptyList() },
    override val localNodeNameState: StateFlow<String?>? = null,
    override val incomingEvents: suspend () -> Flow<IncomingMessageEvent>,
    override val statusEvents: suspend () -> Flow<MessageStatusEvent>,
    override val activeConversation: ActiveConversationSink = ActiveConversationSink.NONE,
) : ChatThreadGraphHost
