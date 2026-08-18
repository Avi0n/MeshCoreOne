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

  /// Populates `writer`'s coordinator and bakes render items. Owns the
  /// loading bracket. `anchorSortDate` is nil for a first-page open; a
  /// refresh passes the oldest loaded `sortDate` so paged-in history stays.
  static func populate(
    _ conversation: ChatConversationType,
    writer: ChatTimelineWriter,
    dataStore: DataStore?,
    bake: ChatMessageBakeState,
    envInputs: EnvInputs,
    senderTables: ChatSenderTables,
    postApply: (@MainActor () -> Void)?,
    anchorSortDate: Date?
  ) async -> Outcome {
    writer.beginLoading()

    guard let dataStore else {
      writer.markLoaded()
      return .unavailable
    }

    // Clear a stuck prepend spinner from a loadOlder that raced a disconnect.
    // Leave the loaded window intact: the next refresh reads `min(sortDate)` from it.
    writer.updateRenderState { $0.with(isLoadingOlder: false) }

    do {
      #if DEBUG
        if let error = writer.testPopulateFetchError {
          throw error
        }
      #endif
      let unreadCount = await currentUnreadCount(for: conversation, dataStore: dataStore)
      let isDM: Bool
      let floorLimit = ChatCoordinator.initialPageSize(unreadCount: unreadCount)
      let window: (messages: [MessageDTO], hasMore: Bool)

      switch conversation {
      case let .dm(contact):
        isDM = true
        window = try await dataStore.fetchMessageWindow(
          contactID: contact.id,
          anchorSortDate: anchorSortDate,
          floorLimit: floorLimit
        )
      case let .channel(channel):
        isDM = false
        window = try await dataStore.fetchMessageWindow(
          radioID: channel.radioID,
          channelIndex: channel.index,
          anchorSortDate: anchorSortDate,
          floorLimit: floorLimit
        )
      }

      #if DEBUG
        await writer.testPopulateAfterFetchHook?()
      #endif
      try Task.checkCancellation()

      var fetchedMessages = window.messages
      let unfilteredCount = fetchedMessages.count

      // Divider from the unfiltered fetch so a hidden outgoing reaction at
      // the boundary still places the line at the correct visual index.
      bake.computeDividerPosition(from: fetchedMessages, unreadCount: unreadCount, isDM: isDM)
      fetchedMessages = bake.filterOutgoingReactionMessages(fetchedMessages, isDM: isDM)

      writer.updateRenderState {
        $0.with(
          hasMoreMessages: window.hasMore,
          totalFetchedCount: unfilteredCount
        )
      }
      writer.replaceAll(fetchedMessages)

      bake.bakeAll(
        messages: fetchedMessages,
        writer: writer,
        envInputs: envInputs,
        senderTables: senderTables,
        postApply: postApply
      )

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

  /// The pushed DTO's unread count can be stale by the time populate runs
  /// (messages arriving during the push transition), which would misplace the
  /// divider and undersize the first page. Read the store's current count,
  /// falling back to the DTO's when the lookup misses. Safe on a live open:
  /// `loadMessages` clears unread only after populate returns.
  private static func currentUnreadCount(
    for conversation: ChatConversationType,
    dataStore: DataStore
  ) async -> Int {
    switch conversation {
    case let .dm(contact):
      let fresh = await (try? dataStore.fetchContact(id: contact.id)) ?? nil
      return fresh?.unreadCount ?? contact.unreadCount
    case let .channel(channel):
      let fresh = await (try? dataStore.fetchChannel(radioID: channel.radioID, index: channel.index)) ?? nil
      return fresh?.unreadCount ?? channel.unreadCount
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
