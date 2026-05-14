import Foundation
import MC1Services

extension ChatViewModel {

    // MARK: - Combined Conversations

    /// Combined conversations (contacts + channels + rooms) - favorites first
    var allConversations: [Conversation] {
        favoriteConversations + nonFavoriteConversations
    }

    /// Favorite conversations sorted by last message date
    var favoriteConversations: [Conversation] {
        rebuildConversationCacheIfNeeded()
        touchObservationDependencies()
        return cachedFavoriteConversations
    }

    /// Non-favorite conversations sorted by last message date
    var nonFavoriteConversations: [Conversation] {
        rebuildConversationCacheIfNeeded()
        touchObservationDependencies()
        return cachedNonFavoriteConversations
    }

    // MARK: - Conversation Cache

    /// Fallback date for conversations with no messages, used to sort them to the end.
    static let noMessageSentinel = Date.distantPast

    /// Invalidates the conversation cache, forcing rebuild on next access
    func invalidateConversationCache() {
        conversationCacheValid = false
    }

    /// Touch source arrays to maintain observation dependencies even when cache is valid.
    /// Without this, SwiftUI won't track changes after initial render because
    /// @ObservationIgnored cache properties don't register dependencies.
    func touchObservationDependencies() {
        _ = conversations.count
        _ = channels.count
        _ = roomSessions.count
    }

    func rebuildConversationCacheIfNeeded() {
        guard !conversationCacheValid else { return }

        let contactConversations = conversations
            .filter { $0.type != .repeater && !$0.isBlocked }
            .map { Conversation.direct($0) }
        let channelConversations = channels
            .filter { !$0.name.isEmpty || $0.hasSecret }
            .map { Conversation.channel($0) }
        let roomConversations = roomSessions.map { Conversation.room($0) }
        let all = contactConversations + channelConversations + roomConversations

        cachedFavoriteConversations = sortedByLastMessage(all.filter { $0.isFavorite })
        cachedNonFavoriteConversations = sortedByLastMessage(all.filter { !$0.isFavorite })

        conversationCacheValid = true
    }

    /// Sorts conversations by last message date, most recent first.
    func sortedByLastMessage(_ items: [Conversation]) -> [Conversation] {
        items.sorted { ($0.lastMessageDate ?? Self.noMessageSentinel) > ($1.lastMessageDate ?? Self.noMessageSentinel) }
    }
}
