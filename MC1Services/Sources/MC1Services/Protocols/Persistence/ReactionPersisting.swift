import Foundation

/// Store operations for message reactions and their summary cache.
public protocol ReactionPersisting: Actor {

    /// Fetch reactions for a message, ordered by most recent first
    func fetchReactions(for messageID: UUID, limit: Int) async throws -> [ReactionDTO]

    /// Save a new reaction
    func saveReaction(_ dto: ReactionDTO) async throws

    /// Check if a reaction already exists (deduplication)
    func reactionExists(messageID: UUID, senderName: String, emoji: String) async throws -> Bool

    /// Update a message's reaction summary cache
    func updateMessageReactionSummary(messageID: UUID, summary: String?) async throws

    /// Delete all reactions for a message
    func deleteReactionsForMessage(messageID: UUID) async throws
}

// MARK: - Default Parameter Values

public extension ReactionPersisting {
    /// Fetch reactions with default limit of 100
    func fetchReactions(for messageID: UUID) async throws -> [ReactionDTO] {
        try await fetchReactions(for: messageID, limit: 100)
    }
}
