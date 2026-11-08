public extension ChatSendQueueService {
  /// Creates and queues a DM. Creation errors throw; enqueue errors retain the saved Message.
  /// The synchronous callback publishes the Message before queue status events can arrive.
  func enqueueDirectMessage(
    text: String,
    to contact: ContactDTO,
    onMessageCreated: (@MainActor (MessageDTO) -> Void)? = nil
  ) async throws -> ChatSendAdmission {
    guard contact.radioID == radioID, beginAdmission() else {
      throw ChatSendQueueServiceError.notConnected
    }
    defer { finishAdmission() }

    let message = try await messageService.createPendingMessage(text: text, to: contact)
    onMessageCreated?(message)
    do {
      try await enqueueDM(DirectMessageEnvelope(messageID: message.id, contactID: contact.id))
      return .queued(message)
    } catch {
      return await recordAdmissionFailure(message: message, error: error)
    }
  }

  /// Creates and queues a channel Message with the same failure and publication contract as a DM.
  func enqueueChannelMessage(
    text: String,
    to channel: ChannelDTO,
    localNodeName: String?,
    onMessageCreated: (@MainActor (MessageDTO) -> Void)? = nil
  ) async throws -> ChatSendAdmission {
    guard channel.radioID == radioID, beginAdmission() else {
      throw ChatSendQueueServiceError.notConnected
    }
    defer { finishAdmission() }

    let message = try await messageService.createPendingChannelMessage(
      text: text, channelIndex: channel.index, radioID: channel.radioID
    )
    onMessageCreated?(message)
    do {
      try await enqueueChannel(ChannelMessageEnvelope(
        messageID: message.id,
        channelIndex: channel.index,
        isResend: false,
        messageText: message.text,
        messageTimestamp: message.timestamp,
        localNodeName: localNodeName
      ))
      return .queued(message)
    } catch {
      return await recordAdmissionFailure(message: message, error: error)
    }
  }

  private func recordAdmissionFailure(message: MessageDTO, error: any Error) async -> ChatSendAdmission {
    // A PendingSend saved before retirement remains available to reconnect hydration.
    _ = try? await dataStore.updateMessageStatusUnlessDelivered(id: message.id, status: .failed)
    return .enqueueFailed(message, error: error)
  }
}
