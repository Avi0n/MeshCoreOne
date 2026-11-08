import Foundation
import MeshCore

public extension MessageService {
  // MARK: - Send Channel Message

  /// Sends a broadcast message to a channel.
  ///
  /// Channel messages are broadcast to all devices listening on the specified channel.
  /// A radio acknowledgement confirms acceptance for transmission.
  ///
  /// - Parameters:
  ///   - text: The message text to broadcast. Bounded by
  ///     `ProtocolLimits.maxChannelMessageLength(nodeNameByteCount:)`.
  ///   - channelIndex: The channel index (0-7)
  ///   - radioID: The local device ID
  ///   - textType: The text encoding type (defaults to `.plain`)
  ///
  /// - Returns: The ID of the created message
  ///
  /// - Throws:
  ///   - `MessageServiceError.messageTooLong` if user text exceeds
  ///     `ProtocolLimits.maxChannelMessageLength(nodeNameByteCount:)`
  ///   - `MessageServiceError.channelNotFound` if channel index is invalid
  ///   - `MessageServiceError.sessionError` if MeshCore send fails
  ///
  /// # Example
  ///
  /// ```swift
  /// let messageID = try await messageService.sendChannelMessage(
  ///     text: "Hello channel!",
  ///     channelIndex: 0,
  ///     radioID: device.id
  /// )
  /// ```
  func sendChannelMessage(
    text: String,
    channelIndex: UInt8,
    radioID: UUID,
    textType: TextType = .plain
  ) async throws -> (id: UUID, timestamp: UInt32) {
    // Keep this send in the retirement barrier through the Sent write if scope cleanup triggers recovery.
    try beginInlineSend()
    defer { endInlineSend() }
    try await rejectIfChannelTextTooLong(text, radioID: radioID)

    let messageID = UUID()
    let timestamp = UInt32(Date().timeIntervalSince1970)

    // Save message to store as pending first
    let messageDTO = createOutgoingChannelMessage(
      id: messageID,
      radioID: radioID,
      channelIndex: channelIndex,
      text: text,
      timestamp: timestamp,
      textType: textType
    )
    try await dataStore.saveMessage(messageDTO)

    do {
      try await withPoolBackoff(transientCode: FirmwareDeviceErrorCode.channelMessageNotFound, config: config.poolBackoff, logger: logger) {
        try await sendChannelMessageWithScope(
          text: text,
          channelIndex: channelIndex,
          radioID: radioID,
          timestamp: Date(timeIntervalSince1970: TimeInterval(timestamp))
        )
      }
    } catch {
      statusEventBroadcaster.yield(.failed(messageID: messageID))
      try await failMessageAndRethrow(error, messageID: messageID)
    }

    #if DEBUG
      if let gate = inlineSendAcceptedGateForTesting { await gate() }
    #endif

    // Preserve radio acceptance if updating the local record fails.
    do {
      try await dataStore.updateMessageStatus(id: messageID, status: .sent)
      statusEventBroadcaster.yield(.statusResolved(messageID: messageID, status: .sent, roundTripTime: nil))
      if let channel = try await dataStore.fetchChannel(radioID: radioID, index: channelIndex) {
        try await dataStore.updateChannelLastMessage(channelID: channel.id, date: Date())
      }
    } catch {
      logger.warning("Inline channel bookkeeping failed after radio acceptance messageID=\(messageID): \(String(describing: error))")
    }

    return (id: messageID, timestamp: timestamp)
  }

  /// Validates the radio's current text budget and saves the Message before queue admission.
  internal func createPendingChannelMessage(
    text: String,
    channelIndex: UInt8,
    radioID: UUID,
    textType: TextType = .plain
  ) async throws -> MessageDTO {
    try await rejectIfChannelTextTooLong(text, radioID: radioID)

    let messageID = UUID()
    let timestamp = UInt32(Date().timeIntervalSince1970)

    let messageDTO = createOutgoingChannelMessage(
      id: messageID,
      radioID: radioID,
      channelIndex: channelIndex,
      text: text,
      timestamp: timestamp,
      textType: textType
    )
    try await dataStore.saveMessage(messageDTO)

    return messageDTO
  }

  /// Sends an already-created pending channel message.
  ///
  /// Use this after ``createPendingChannelMessage(text:channelIndex:radioID:textType:)``
  /// to transmit the message over the mesh. Updates the message status to `.sent` on
  /// success or `.failed` on error.
  ///
  /// - Parameter messageID: The ID of the pending message to send
  func sendPendingChannelMessage(messageID: UUID, expectedChannelSecret: Data? = nil) async throws {
    // Catch 1: validation guards + send + status flip. Validation lives
    // inside the do/catch so a missing message or missing channel index
    // consistently writes `.failed` to the DB before propagating — paired
    // with the queue's outer catch, which fires `notifyMessageFailed`.
    // A failure before .sent is committed means the user should see
    // .failed (retry button visible).
    let radioID: UUID
    let channelIndex: UInt8
    do {
      guard let message = try await dataStore.fetchMessage(id: messageID) else {
        throw MessageServiceError.sendFailed("Message not found")
      }
      guard let idx = message.channelIndex else {
        throw MessageServiceError.sendFailed("Not a channel message")
      }
      radioID = message.radioID
      channelIndex = idx

      let isReaction = message.reactionTargetMessageID != nil
      let alreadyAccepted = isReaction && (acceptedChannelReactions.contains(messageID) || message.status == .sent)
      if !alreadyAccepted {
        try await withPoolBackoff(transientCode: FirmwareDeviceErrorCode.channelMessageNotFound, config: config.poolBackoff, logger: logger) {
          try await sendChannelMessageWithScope(
            text: message.text,
            channelIndex: channelIndex,
            radioID: radioID,
            timestamp: Date(timeIntervalSince1970: TimeInterval(message.timestamp)),
            expectedChannelSecret: expectedChannelSecret
          )
        }
        if isReaction { acceptedChannelReactions.insert(messageID) }
      }
      do {
        try await dataStore.updateMessageStatus(id: messageID, status: .sent)
        if isReaction {
          await notifyOutgoingReactionAccepted(messageID: messageID)
          acceptedChannelReactions.remove(messageID)
        }
      } catch {
        if isReaction { throw MessageServiceError.persistenceError(error) }
        throw error
      }
    } catch {
      if case MessageServiceError.persistenceError = error { throw error }
      try await failMessageAndRethrow(error, messageID: messageID)
    }

    // Catch 2: post-status bookkeeping. Status is already .sent in DB; the
    // radio broadcast happened. A failure here is logged but does not mark
    // .failed — that would corrupt the user-visible delivery state. The
    // bookkeeping (channel last-message timestamp, sent event) is
    // best-effort metadata that next-load or next-ack will reconverge.
    do {
      statusEventBroadcaster.yield(.statusResolved(messageID: messageID, status: .sent, roundTripTime: nil))
      if let channel = try await dataStore.fetchChannel(radioID: radioID, index: channelIndex) {
        try await dataStore.updateChannelLastMessage(channelID: channel.id, date: Date())
      }
    } catch {
      logger.warning("Channel post-status bookkeeping failed messageID=\(messageID) status=.sent already committed: \(String(describing: error))")
    }
  }

  /// Resends a channel message, preserving its wire timestamp only during recovery.
  /// Returns that timestamp so reaction indexing matches the send.
  @discardableResult
  func resendChannelMessage(
    messageID: UUID,
    preserveTimestamp: Bool = false,
    expectedChannelSecret: Data? = nil
  ) async throws -> UInt32 {
    // Publish .resent after .sent commits, even if send-count bookkeeping fails.
    var sentCommitted = false

    let wireTimestamp: UInt32
    do {
      guard let message = try await dataStore.fetchMessage(id: messageID) else {
        throw MessageServiceError.sendFailed("Message not found")
      }
      guard let channelIndex = message.channelIndex else {
        throw MessageServiceError.sendFailed("Not a channel message")
      }

      let wireDate: Date
      if preserveTimestamp {
        wireTimestamp = message.timestamp
        wireDate = Date(timeIntervalSince1970: TimeInterval(wireTimestamp))
      } else {
        let now = Date()
        wireTimestamp = UInt32(now.timeIntervalSince1970)
        wireDate = now
      }

      if !preserveTimestamp {
        try await dataStore.prepareChannelMessageResend(id: messageID, timestamp: wireTimestamp)
      }
      let isReaction = message.reactionTargetMessageID != nil
      let alreadyAccepted = isReaction && (
        acceptedChannelReactions.contains(messageID) || (preserveTimestamp && message.status == .sent)
      )
      if !alreadyAccepted {
        try await withPoolBackoff(transientCode: FirmwareDeviceErrorCode.channelMessageNotFound, config: config.poolBackoff, logger: logger) {
          try await sendChannelMessageWithScope(
            text: message.text,
            channelIndex: channelIndex,
            radioID: message.radioID,
            timestamp: wireDate,
            expectedChannelSecret: expectedChannelSecret
          )
        }
        if isReaction { acceptedChannelReactions.insert(messageID) }
      }
      do {
        try await dataStore.updateMessageStatus(id: messageID, status: .sent)
        if isReaction {
          await notifyOutgoingReactionAccepted(messageID: messageID)
          acceptedChannelReactions.remove(messageID)
        }
      } catch {
        if isReaction { throw MessageServiceError.persistenceError(error) }
        throw error
      }
      sentCommitted = true
    } catch {
      if case MessageServiceError.persistenceError = error { throw error }
      try await failMessageAndRethrow(error, messageID: messageID)
    }

    // Status is already .sent. A send-count failure cannot undo radio acceptance.
    do {
      _ = try await dataStore.incrementMessageSendCount(id: messageID)
    } catch {
      logger.warning("Resend post-status bookkeeping failed messageID=\(messageID) status=.sent already committed: \(String(describing: error))")
    }

    if sentCommitted {
      statusEventBroadcaster.yield(.resent(messageID: messageID))
    }

    return wireTimestamp
  }

  private func createOutgoingChannelMessage(
    id: UUID,
    radioID: UUID,
    channelIndex: UInt8,
    text: String,
    timestamp: UInt32,
    textType: TextType
  ) -> MessageDTO {
    let message = Message(
      id: id,
      radioID: radioID,
      channelIndex: channelIndex,
      text: text,
      timestamp: timestamp,
      directionRawValue: MessageDirection.outgoing.rawValue,
      statusRawValue: MessageStatus.pending.rawValue,
      textTypeRawValue: textType.rawValue
    )
    return MessageDTO(from: message)
  }

  /// Cap user text so `"name: " + text` fits `maxChannelMessageTotalLength`.
  /// Firmware prepends the name before encrypting; an extra AES block overflows the RX log.
  func rejectIfChannelTextTooLong(_ text: String, radioID: UUID) async throws {
    let nodeNameByteCount: Int = if let name = try? await dataStore.fetchDevice(radioID: radioID)?.nodeName {
      name.utf8.count
    } else {
      ProtocolLimits.maxUsableNameBytes
    }
    let maxBytes = ProtocolLimits.maxChannelMessageLength(nodeNameByteCount: nodeNameByteCount)
    guard text.utf8.count <= maxBytes else {
      throw MessageServiceError.messageTooLong
    }
  }
}
