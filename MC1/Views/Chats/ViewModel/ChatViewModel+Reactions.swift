import Foundation
import MC1Services

extension ChatViewModel {
  // MARK: - Reactions

  /// Send a reaction emoji to a message (channel or DM)
  func sendReaction(emoji: String, to message: MessageDTO) async {
    guard reactionServiceProvider() != nil,
          messageService != nil,
          let dataStore else {
      return
    }

    // Prevent duplicate sends from rapid taps
    let reactionKey = "\(message.id)-\(emoji)"
    guard !inFlightReactions.contains(reactionKey) else {
      logger.debug("Reaction \(emoji) already in flight for message \(message.id), ignoring")
      return
    }
    inFlightReactions.insert(reactionKey)
    defer { inFlightReactions.remove(reactionKey) }

    let localNodeName = connectedDeviceProvider()?.nodeName ?? "Me"

    // Check if user already reacted with this emoji
    if let alreadyReacted = try? await dataStore.reactionExists(
      messageID: message.id,
      senderName: localNodeName,
      emoji: emoji
    ), alreadyReacted {
      logger.debug("User already reacted with \(emoji), ignoring")
      return
    }

    // Handle channel vs DM
    if let channelIndex = message.channelIndex {
      await sendChannelReaction(
        emoji: emoji,
        to: message,
        channelIndex: channelIndex,
        localNodeName: localNodeName
      )
    } else if let contactID = message.contactID {
      await sendDMReaction(
        emoji: emoji,
        to: message,
        contactID: contactID,
        localNodeName: localNodeName
      )
    }
  }

  private func sendChannelReaction(
    emoji: String,
    to message: MessageDTO,
    channelIndex: UInt8,
    localNodeName: String
  ) async {
    guard let reactionService = reactionServiceProvider(),
          let messageService,
          let dataStore else { return }

    // Determine target sender name
    let targetSenderName: String
    if message.isOutgoing {
      targetSenderName = localNodeName
    } else {
      guard let senderName = message.senderNodeName else { return }
      targetSenderName = senderName
    }

    let reactionText = reactionService.buildReactionText(
      emoji: emoji,
      targetSender: targetSenderName,
      targetText: message.text,
      targetTimestamp: message.reactionTimestamp
    )

    do {
      _ = try await messageService.sendChannelMessage(
        text: reactionText,
        channelIndex: channelIndex,
        radioID: message.radioID
      )

      // Optimistic local update
      let messageHash = ReactionParser.generateMessageHash(
        text: message.text,
        timestamp: message.reactionTimestamp
      )
      let reactionDTO = ReactionDTO(
        messageID: message.id,
        emoji: emoji,
        senderName: localNodeName,
        messageHash: messageHash,
        rawText: reactionText,
        channelIndex: channelIndex,
        radioID: message.radioID
      )
      if let result = await reactionService.persistReactionAndUpdateSummary(
        reactionDTO,
        using: dataStore
      ) {
        updateReactionSummary(for: result.messageID, summary: result.summary)
      }
    } catch {
      logger.error("Failed to send channel reaction: \(error)")
      errorMessage = error.userFacingMessage
    }
  }

  private func sendDMReaction(
    emoji: String,
    to message: MessageDTO,
    contactID: UUID,
    localNodeName: String
  ) async {
    guard let reactionService = reactionServiceProvider(),
          let messageService,
          let dataStore else { return }

    // Fetch the contact for sending
    guard let contact = try? await dataStore.fetchContact(id: contactID) else {
      logger.error("Failed to fetch contact for DM reaction")
      return
    }

    let reactionText = reactionService.buildDMReactionText(
      emoji: emoji,
      targetText: message.text,
      targetTimestamp: message.reactionTimestamp
    )
    do {
      // Send as DM to the contact
      _ = try await messageService.sendDirectMessage(
        text: reactionText,
        to: contact
      )

      // Optimistic local update
      let messageHash = ReactionParser.generateMessageHash(
        text: message.text,
        timestamp: message.reactionTimestamp
      )
      let reactionDTO = ReactionDTO(
        messageID: message.id,
        emoji: emoji,
        senderName: localNodeName,
        messageHash: messageHash,
        rawText: reactionText,
        contactID: contactID,
        radioID: message.radioID
      )
      if let result = await reactionService.persistReactionAndUpdateSummary(
        reactionDTO,
        using: dataStore
      ) {
        updateReactionSummary(for: result.messageID, summary: result.summary)
      }
    } catch {
      logger.error("Failed to send DM reaction: \(error)")
      errorMessage = error.userFacingMessage
    }
  }

  // MARK: - Reaction Filtering

  /// Filter out outgoing reaction messages unless they failed to send.
  /// Reaction messages are hidden from the UI to avoid clutter since they're displayed as badges.
  /// - Parameters:
  ///   - messages: The messages to filter
  ///   - isDM: Whether these are DM messages (uses parseDM) or channel messages (uses parse)
  /// - Returns: Filtered messages with successful outgoing reactions removed
  func filterOutgoingReactionMessages(_ messages: [MessageDTO], isDM: Bool) -> [MessageDTO] {
    messages.filter { !isHiddenOutgoingReaction($0, isDM: isDM) }
  }

  /// Whether a message is a successfully-sent outgoing reaction, which is rendered
  /// as a badge and so hidden from the timeline by `filterOutgoingReactionMessages`.
  /// Failed reactions stay visible so the user can retry them.
  func isHiddenOutgoingReaction(_ message: MessageDTO, isDM: Bool) -> Bool {
    guard message.direction == .outgoing else { return false }

    let isReaction = isDM
      ? ReactionParser.parseDM(message.text) != nil
      : ReactionParser.parse(message.text) != nil

    guard isReaction else { return false }

    return message.status != .failed
  }

  // MARK: - Reaction Updates

  /// Update reaction summary for a specific message inline (O(1) update)
  func updateReactionSummary(for messageID: UUID, summary: String) {
    updateMessage(id: messageID) { $0.reactionSummary = summary }
  }
}
