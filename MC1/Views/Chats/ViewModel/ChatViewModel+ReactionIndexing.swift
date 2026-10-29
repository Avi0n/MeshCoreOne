import Foundation
import MC1Services

extension ChatViewModel {
  // MARK: - Reaction Indexing

  /// Indexes reaction targets and persists pending matches.
  /// Reloads committed rows so older summaries cannot overwrite the timeline.
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
      writer: timelineWriter
    )
  }
}
