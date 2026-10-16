package one.meshcore.shared.chat

import one.meshcore.shared.model.ContactType
import one.meshcore.shared.remotenode.isRoom

/**
 * Recomputes the pinned/other snapshot from the buffers in one pass, excluding [pendingRemovalIds].
 * Bumps [snapshotGeneration] only when the committed snapshot changes — a value-identical recompute
 * (e.g. a stale reload masked by [pendingRemovalIds]) leaves the generation untouched (the change-gate).
 */
internal fun ConversationListState.recomputed(): ConversationListState {
    // Repeaters live under Nodes. A room-typed contact with DMs is still a Direct row (iOS
    // `recomputeSnapshot`); the room session is a separate conversation for room posts.
    val directs = contacts
        .filter {
            it.typeRawValue != ContactType.REPEATER.raw &&
                !it.isBlocked &&
                it.id !in pendingRemovalIds
        }
        .map { Conversation.Direct(it) }
    val channelConvos = channels
        .filter { (it.name.isNotEmpty() || it.hasSecret) && it.id !in pendingRemovalIds }
        .map { Conversation.Channel(it) }
    // Only a room server is a conversation. A repeater-role session exists for every admin login, and
    // listing one adds a row that opens the room pane on a node that has no room messages at all.
    val roomConvos = rooms
        .filter { it.isRoom && it.id !in pendingRemovalIds }
        .map { Conversation.Room(it) }
    val all = directs + channelConvos + roomConvos

    val next = ConversationSnapshot(
        pinned = sortedByLastMessage(all.filter { it.isPinned }),
        others = sortedByLastMessage(all.filter { !it.isPinned }),
    )
    return if (next == snapshot) this else copy(snapshot = next, snapshotGeneration = snapshotGeneration + 1)
}

/** Drops masked ids the fresh fetch confirms are gone, so a confirmed deletion self-heals. */
internal fun ConversationListState.reconciledPendingRemovals(): ConversationListState {
    if (pendingRemovalIds.isEmpty()) return this
    val present = (contacts.map { it.id } + channels.map { it.id } + rooms.map { it.id }).toSet()
    return copy(pendingRemovalIds = pendingRemovalIds intersect present)
}
