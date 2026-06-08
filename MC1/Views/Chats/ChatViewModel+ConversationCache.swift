import Foundation
import MC1Services

extension ChatViewModel {

    // MARK: - Combined Conversations

    /// Combined conversations (contacts + channels + rooms) - favorites first
    var allConversations: [Conversation] {
        conversationSnapshot.favorites + conversationSnapshot.others
    }

    /// Favorite conversations sorted by last message date
    var favoriteConversations: [Conversation] { conversationSnapshot.favorites }

    /// Non-favorite conversations sorted by last message date
    var nonFavoriteConversations: [Conversation] { conversationSnapshot.others }

    // MARK: - Snapshot Recompute

    /// Fallback date for conversations with no messages, used to sort them to the end.
    static let noMessageSentinel = Date.distantPast

    /// Recomputes the observed snapshot from the fetch buffers in one synchronous pass,
    /// excluding `pendingRemovalIDs` so a just-deleted row stays hidden across a racing reload.
    /// Never wraps the assignment in `withAnimation`; only `removeConversation`/`restoreConversation`
    /// do, so a delete animates once while reload-driven recomputes stay unanimated.
    func recomputeSnapshot() {
        let contactConversations = conversations
            .filter { $0.type != .repeater && !$0.isBlocked && !pendingRemovalIDs.contains($0.id) }
            .map { Conversation.direct($0) }
        let channelConversations = channels
            .filter { (!$0.name.isEmpty || $0.hasSecret) && !pendingRemovalIDs.contains($0.id) }
            .map { Conversation.channel($0) }
        let roomConversations = roomSessions
            .filter { !pendingRemovalIDs.contains($0.id) }
            .map { Conversation.room($0) }
        let all = contactConversations + channelConversations + roomConversations

        let newSnapshot = ConversationSnapshot(
            favorites: sortedByLastMessage(all.filter { $0.isFavorite }),
            others: sortedByLastMessage(all.filter { !$0.isFavorite })
        )

        // Skip republishing an identical snapshot to avoid a needless re-diff. Safe because
        // pendingRemovalIDs masks a deleted row, so a stale reload recomputes this same snapshot
        // rather than a row-present one that would slip past the guard.
        guard newSnapshot != conversationSnapshot else { return }

        conversationSnapshot = newSnapshot
        snapshotGeneration &+= 1
    }

    /// Drops pending ids the fresh fetch confirms are gone. Run at the top of every
    /// reload commit so a confirmed deletion self-heals and the set can't leak.
    func reconcilePendingRemovals() {
        guard !pendingRemovalIDs.isEmpty else { return }
        let presentIDs = Set(conversations.map(\.id))
            .union(channels.map(\.id))
            .union(roomSessions.map(\.id))
        pendingRemovalIDs.formIntersection(presentIDs)
    }

    /// Sorts conversations by last message date, most recent first.
    func sortedByLastMessage(_ items: [Conversation]) -> [Conversation] {
        items.sorted { ($0.lastMessageDate ?? Self.noMessageSentinel) > ($1.lastMessageDate ?? Self.noMessageSentinel) }
    }
}
