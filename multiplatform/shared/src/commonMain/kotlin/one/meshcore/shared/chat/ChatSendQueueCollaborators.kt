package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.model.Uuid

/** The store, sender, clock, and id factory one send queue drains with. */
data class ChatSendQueueCollaborators(
    val store: SendQueueStore,
    val messageService: MessageService,
    val channelResolver: ChannelResolver,
    val scope: CoroutineScope,
    val now: () -> Double,
    val newPendingSendId: () -> Uuid,
)
