import Foundation
import MC1Services

extension ChatViewModel {
  // MARK: - Reaction Indexing

  /// Conversation keying for `indexMessagesForReactions`: channels match by
  /// (channel index, sender name) and persist `channelIndex`; DMs match by
  /// contact and persist `contactID`.
  enum ReactionIndexScope {
    case channel(ChannelDTO, localNodeName: String?)
    case direct(ContactDTO)
  }

  /// Indexes a freshly fetched page of messages for reaction matching and
  /// persists any pending reactions that now have their target, applying the
  /// refreshed summaries to the in-memory timeline. Awaits the
  /// `ReactionService` actor serially per message, so callers run it after
  /// `buildItems()` to keep the visible timeline from being gated on it.
  func indexMessagesForReactions(
    _ fetchedMessages: [MessageDTO],
    scope: ReactionIndexScope,
    reactionService: ReactionService,
    dataStore: DataStore
  ) async {
    switch scope {
    case let .channel(channel, localNodeName):
      // The channel's own radioID, never the live connection's: a mid-load
      // disconnect would otherwise mint a fresh UUID into persisted rows.
      let radioID = channel.radioID
      for message in fetchedMessages {
        let senderName: String? = if message.isOutgoing {
          localNodeName
        } else {
          message.senderNodeName
        }
        guard let senderName else { continue }

        let pendingMatches = await reactionService.indexMessage(
          id: message.id,
          channelIndex: channel.index,
          senderName: senderName,
          text: message.text,
          timestamp: message.timestamp
        )

        // Process any pending reactions that now have their target
        for pending in pendingMatches {
          let reactionDTO = ReactionDTO(
            messageID: message.id,
            emoji: pending.parsed.emoji,
            senderName: pending.senderNodeName,
            messageHash: pending.parsed.messageHash,
            rawText: pending.rawText,
            channelIndex: pending.channelIndex,
            radioID: radioID
          )
          await persistPendingReactionIfNew(
            reactionDTO,
            reactionService: reactionService,
            dataStore: dataStore
          )
        }
      }

    case let .direct(contact):
      for message in fetchedMessages {
        let pendingMatches = await reactionService.indexDMMessage(
          id: message.id,
          contactID: contact.id,
          text: message.text,
          timestamp: message.reactionTimestamp
        )

        // Process any pending reactions that now have their target
        for pending in pendingMatches {
          let reactionDTO = ReactionDTO(
            messageID: message.id,
            emoji: pending.parsed.emoji,
            senderName: pending.senderName,
            messageHash: pending.parsed.messageHash,
            rawText: pending.rawText,
            contactID: contact.id,
            radioID: contact.radioID
          )
          await persistPendingReactionIfNew(
            reactionDTO,
            reactionService: reactionService,
            dataStore: dataStore
          )
        }
      }
    }
  }

  /// Persists `reaction` unless an identical (message, sender, emoji) row
  /// already exists, then applies the refreshed summary to the in-memory message.
  private func persistPendingReactionIfNew(
    _ reaction: ReactionDTO,
    reactionService: ReactionService,
    dataStore: DataStore
  ) async {
    let exists = try? await dataStore.reactionExists(
      messageID: reaction.messageID,
      senderName: reaction.senderName,
      emoji: reaction.emoji
    )
    guard exists != true else { return }

    if let result = await reactionService.persistReactionAndUpdateSummary(
      reaction,
      using: dataStore
    ) {
      updateReactionSummary(for: result.messageID, summary: result.summary)
    }
  }
}
