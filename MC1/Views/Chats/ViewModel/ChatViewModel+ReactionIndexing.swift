import Foundation
import MC1Services

extension ChatViewModel {
  // MARK: - Reaction Indexing

  /// Indexes a freshly fetched page of messages for reaction matching and
  /// persists any pending reactions that now have their target, applying the
  /// refreshed summaries to the in-memory timeline. Forwards to the shared
  /// populator core with this view model's writer and single-row rebake.
  func indexMessagesForReactions(
    _ fetchedMessages: [MessageDTO],
    scope: ReactionIndexScope,
    reactionService: ReactionService,
    dataStore: DataStore
  ) async {
    guard let timelineWriter else { return }
    await ChatTimelinePopulator.indexMessagesForReactions(
      fetchedMessages,
      scope: scope,
      reactionService: reactionService,
      dataStore: dataStore,
      writer: timelineWriter,
      rebakeRow: { [weak self] messageID in
        self?.rebuildDisplayItem(for: messageID)
      }
    )
  }
}
