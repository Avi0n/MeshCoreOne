package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid

/**
 * Sets a conversation's notification level with an optimistic UI write, then persists it; rolls the UI
 * back on failure. Contacts map the level to a boolean mute. Requires a ready connection.
 */
suspend fun ConversationListViewModel.setNotificationLevel(conversation: Conversation, level: NotificationLevel) {
    val store = dependencies.store() ?: return
    if (!dependencies.isReady()) return
    val originalLevel = conversation.notificationLevel

    applyNotificationLevel(conversation, level)
    try {
        when (conversation) {
            is Conversation.Direct -> store.setContactMuted(conversation.id, level == NotificationLevel.MUTED)
            is Conversation.Channel -> store.setChannelNotificationLevel(conversation.id, level)
            is Conversation.Room -> store.setSessionNotificationLevel(conversation.id, level)
        }
        dependencies.updateBadge()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        applyNotificationLevel(conversation, originalLevel)
    }
}

/** Flips a conversation between muted and all (the swipe/quick action). */
suspend fun ConversationListViewModel.toggleMute(conversation: Conversation) {
    val newLevel = if (conversation.isMuted) NotificationLevel.ALL else NotificationLevel.MUTED
    setNotificationLevel(conversation, newLevel)
}

/** Toggles a local chat pin with an optimistic list move and rollback on persistence failure. */
suspend fun ConversationListViewModel.togglePinned(conversation: Conversation) = reloadMutex.withLock {
    val store = dependencies.store() ?: run {
        listState.update { it.copy(errorMessage = messages.pinSaveFailed) }
        return@withLock
    }
    val current = listState.value.snapshot.all.firstOrNull { it.id == conversation.id } ?: return@withLock
    val original = current.isPinned
    val next = !original

    applyPinned(current, next)
    var saved = false
    try {
        when (current) {
            is Conversation.Direct -> store.setContactPinned(current.id, next)
            is Conversation.Channel -> store.setChannelPinned(current.id, next)
            is Conversation.Room -> store.setSessionPinned(current.id, next)
        }
        saved = true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        listState.update { it.copy(errorMessage = messages.pinSaveFailed) }
    } finally {
        if (!saved) applyPinned(current, original)
    }
}

private fun ConversationListViewModel.applyNotificationLevel(conversation: Conversation, level: NotificationLevel) {
    val muted = level == NotificationLevel.MUTED
    mutateBuffer(
        conversation,
        contact = { it.copy(isMuted = muted) },
        channel = { it.copy(notificationLevel = level) },
        room = { it.copy(notificationLevel = level) },
    )
}

private fun ConversationListViewModel.applyPinned(conversation: Conversation, isPinned: Boolean) = mutateBuffer(
    conversation,
    contact = { it.copy(isPinned = isPinned) },
    channel = { it.copy(isPinned = isPinned) },
    room = { it.copy(isPinned = isPinned) },
)

/** Replaces the mutated row in its buffer and recomputes the snapshot in one synchronous write. */
private inline fun ConversationListViewModel.mutateBuffer(
    conversation: Conversation,
    contact: (ContactDto) -> ContactDto,
    channel: (ChannelDto) -> ChannelDto,
    room: (RemoteNodeSessionDto) -> RemoteNodeSessionDto,
) = listState.update { current ->
    val id = conversation.id
    when (conversation) {
        is Conversation.Direct -> current.copy(contacts = current.contacts.replacingContact(id, contact))
        is Conversation.Channel -> current.copy(channels = current.channels.replacingChannel(id, channel))
        is Conversation.Room -> current.copy(rooms = current.rooms.replacingRoom(id, room))
    }.recomputed()
}

private inline fun List<ContactDto>.replacingContact(
    id: Uuid,
    transform: (ContactDto) -> ContactDto,
): List<ContactDto> = map { if (it.id == id) transform(it) else it }

private inline fun List<ChannelDto>.replacingChannel(
    id: Uuid,
    transform: (ChannelDto) -> ChannelDto,
): List<ChannelDto> = map { if (it.id == id) transform(it) else it }

private inline fun List<RemoteNodeSessionDto>.replacingRoom(
    id: Uuid,
    transform: (RemoteNodeSessionDto) -> RemoteNodeSessionDto,
): List<RemoteNodeSessionDto> = map { if (it.id == id) transform(it) else it }
