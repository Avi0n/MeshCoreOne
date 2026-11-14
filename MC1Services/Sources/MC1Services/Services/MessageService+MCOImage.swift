import Foundation

public extension MessageService {
  /// Refreshes a user-initiated image resend before its queue envelope captures the wire text.
  func prepareImageForResend(_ message: MessageDTO) async throws -> MessageDTO {
    guard MCOImageCodec.isImageText(message.text) else { return message }
    var refreshed = message
    let text = message.channelIndex != nil
      ? try MCOImageCodec.text(fromAppPayload: MCOImageCodec.channelAppPayload(fromText: message.text))
      : message.text
    refreshed.text = try MCOImageCodec.refreshNonce(in: text)
    if message.channelIndex != nil {
      _ = try await channelImagePayload(refreshed.text, radioID: message.radioID)
    } else {
      guard refreshed.text.utf8.count <= ProtocolLimits.maxDirectMessageLength else {
        throw MessageServiceError.messageTooLong
      }
    }
    try await dataStore.updateMessageText(id: message.id, text: refreshed.text)
    return refreshed
  }

  internal func prepareChannelMessage(_ text: String, radioID: UUID) async throws -> String {
    guard MCOImageCodec.isImageText(text) else {
      try await rejectIfChannelTextTooLong(text, radioID: radioID)
      return text
    }
    let canonicalText = try MCOImageCodec.text(fromAppPayload: MCOImageCodec.channelAppPayload(fromText: text))
    _ = try await channelImagePayload(canonicalText, radioID: radioID)
    return canonicalText
  }

  internal func channelImagePayload(_ text: String, radioID: UUID) async throws -> Data {
    guard let device = try await dataStore.fetchDevice(radioID: radioID) else {
      throw MessageServiceError.notConnected
    }
    let payload = try MCOImageTransport.encode(text: text, senderName: device.nodeName)
    guard payload.count <= MCOImageWireBudget.maximumChannelDataBytes else {
      throw MessageServiceError.messageTooLong
    }
    return payload
  }
}
