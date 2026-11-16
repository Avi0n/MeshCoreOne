import MC1Services

extension ChatViewModel {
  // MARK: - Reactions

  /// Send a reaction emoji to a message (channel or DM)
  func sendReaction(emoji: String, to message: MessageDTO) async {
    guard let queue = chatSendQueueServiceProvider(),
          let localNodeName = connectedDeviceProvider()?.nodeName else {
      sendErrorMessage = L10n.Chats.Chats.Alert.UnableToSend.message
      return
    }

    let reactionKey = "\(message.id)-\(emoji)"
    guard !inFlightReactions.contains(reactionKey) else { return }
    inFlightReactions.insert(reactionKey)
    defer { inFlightReactions.remove(reactionKey) }

    do {
      _ = try await queue.enqueueReaction(to: message, emoji: emoji, senderName: localNodeName)
    } catch {
      logger.error("Failed to queue reaction: \(error)")
      sendErrorMessage = Self.copyForEnqueueFailure(error)
    }
  }
}
