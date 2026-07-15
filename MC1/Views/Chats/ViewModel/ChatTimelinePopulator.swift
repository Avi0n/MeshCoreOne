import Foundation
import MC1Services

/// Shared fetch → divider → filter → write → bake sequence used by live
/// conversation opens and by `ChatTimelinePrimer`. Stateless; all mutable
/// state lives on the passed writer and bake instance.
@MainActor
enum ChatTimelinePopulator {
  enum Outcome {
    case loaded
    /// `CancellationError`: silent; a superseding load refetches.
    case cancelled
    /// No data store: the never-paired open, which has no error to report.
    case unavailable
    /// Caller decides the surface: user-facing copy on screen, the error
    /// itself in a log.
    case failed(Error)
  }

  struct ReactionIndexingContext {
    let reactionService: ReactionService
    let scope: ReactionIndexScope
    let rebakeRow: @MainActor (UUID) -> Void
  }

  /// Populates `writer`'s coordinator with the first page for `conversation`
  /// and bakes render items. Owns the loading bracket (`beginLoading` /
  /// `markLoaded` on every exit).
  static func populate(
    _ conversation: ChatConversationType,
    writer: ChatTimelineWriter,
    dataStore: DataStore?,
    bake: ChatMessageBakeState,
    envInputs: EnvInputs,
    senderTables: ChatSenderTables,
    reactions: ReactionIndexingContext?,
    postApply: (@MainActor () -> Void)?
  ) async -> Outcome {
    writer.beginLoading()

    guard let dataStore else {
      writer.markLoaded()
      return .unavailable
    }

    // Reset pagination state for the new conversation page.
    writer.updateRenderState { $0.with(hasMoreMessages: true, isLoadingOlder: false, totalFetchedCount: 0) }

    do {
      let unreadCount = conversation.unreadCount
      let isDM: Bool
      let initialLimit = ChatCoordinator.initialPageSize(unreadCount: unreadCount)
      var fetchedMessages: [MessageDTO]

      switch conversation {
      case let .dm(contact):
        isDM = true
        fetchedMessages = try await dataStore.fetchMessages(
          contactID: contact.id,
          limit: initialLimit,
          offset: 0
        )
      case let .channel(channel):
        isDM = false
        fetchedMessages = try await dataStore.fetchMessages(
          radioID: channel.radioID,
          channelIndex: channel.index,
          limit: initialLimit,
          offset: 0
        )
      }

      let unfilteredCount = fetchedMessages.count
      writer.updateRenderState { $0.with(totalFetchedCount: unfilteredCount) }

      // Divider from the unfiltered fetch so a hidden outgoing reaction at
      // the boundary still places the line at the correct visual index.
      bake.computeDividerPosition(from: fetchedMessages, unreadCount: unreadCount, isDM: isDM)
      fetchedMessages = bake.filterOutgoingReactionMessages(fetchedMessages, isDM: isDM)

      writer.updateRenderState { $0.with(hasMoreMessages: unfilteredCount == initialLimit) }
      writer.replaceAll(fetchedMessages)

      bake.bakeAll(
        messages: fetchedMessages,
        writer: writer,
        envInputs: envInputs,
        senderTables: senderTables,
        postApply: postApply
      )

      if let reactions {
        await indexMessagesForReactions(
          fetchedMessages,
          scope: reactions.scope,
          reactionService: reactions.reactionService,
          dataStore: dataStore,
          writer: writer,
          rebakeRow: reactions.rebakeRow
        )
      }

      writer.markLoaded()
      return .loaded
    } catch is CancellationError {
      writer.markLoaded()
      return .cancelled
    } catch {
      writer.markLoaded()
      return .failed(error)
    }
  }

  /// Indexes a freshly fetched page for reaction matching and persists any
  /// pending reactions that now have their target, applying refreshed
  /// summaries through `writer` and a single-row rebake.
  static func indexMessagesForReactions(
    _ fetchedMessages: [MessageDTO],
    scope: ReactionIndexScope,
    reactionService: ReactionService,
    dataStore: DataStore,
    writer: ChatTimelineWriter,
    rebakeRow: @MainActor (UUID) -> Void
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
            dataStore: dataStore,
            writer: writer,
            rebakeRow: rebakeRow
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
            dataStore: dataStore,
            writer: writer,
            rebakeRow: rebakeRow
          )
        }
      }
    }
  }

  private static func persistPendingReactionIfNew(
    _ reaction: ReactionDTO,
    reactionService: ReactionService,
    dataStore: DataStore,
    writer: ChatTimelineWriter,
    rebakeRow: @MainActor (UUID) -> Void
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
      writer.update(messageID: result.messageID) { $0.reactionSummary = result.summary }
      rebakeRow(result.messageID)
    }
  }
}
