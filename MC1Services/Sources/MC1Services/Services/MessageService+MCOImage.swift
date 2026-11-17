import Foundation

extension MessageService {
  enum ChannelTransmission {
    case text(String)
    case mcoImage(Data)
  }

  /// MCOimg bytes use the stored sender name, so a later rename does not change the payload.
  func channelTransmission(
    text: String,
    senderNodeName: String?,
    radioID: UUID
  ) async throws -> ChannelTransmission {
    if MCOImageCodec.isMCOImageText(text) {
      return try .mcoImage(channelMCOImagePayload(text, senderName: senderNodeName ?? ""))
    }
    try await rejectIfChannelTextTooLong(text, radioID: radioID)
    return .text(text)
  }
}

public extension MessageService {
  /// Refreshes a user-initiated MCOimg resend before its queue envelope captures the wire text.
  func prepareMCOImageForResend(_ message: MessageDTO) async throws -> MessageDTO {
    guard message.mcoImage != nil else { return message }
    var refreshed = message
    let text = message.channelIndex != nil
      ? try MCOImageCodec.text(fromAppPayload: MCOImageCodec.channelAppPayload(fromText: message.text))
      : message.text
    refreshed.text = try MCOImageCodec.refreshNonce(in: text)
    if message.channelIndex != nil {
      _ = try channelMCOImagePayload(refreshed.text, senderName: message.senderNodeName ?? "")
    } else {
      guard refreshed.text.utf8.count <= ProtocolLimits.maxDirectMessageLength else {
        throw MessageServiceError.messageTooLong
      }
    }
    try await dataStore.updateMessageText(id: message.id, text: refreshed.text)
    return refreshed
  }

  internal func prepareChannelMessage(
    _ text: String,
    radioID: UUID
  ) async throws -> (text: String, mcoImageSenderName: String?) {
    guard MCOImageCodec.isMCOImageText(text) else {
      try await rejectIfChannelTextTooLong(text, radioID: radioID)
      return (text, nil)
    }
    let canonicalText = try MCOImageCodec.text(fromAppPayload: MCOImageCodec.channelAppPayload(fromText: text))
    guard let device = try await dataStore.fetchDevice(radioID: radioID) else {
      throw MessageServiceError.notConnected
    }
    _ = try channelMCOImagePayload(canonicalText, senderName: device.nodeName)
    return (canonicalText, device.nodeName)
  }

  internal func channelMCOImagePayload(_ text: String, senderName: String) throws -> Data {
    let payload = try MCOImageTransport.encode(text: text, senderName: senderName)
    guard payload.count <= MCOImageWireBudget.maximumChannelDataBytes else {
      throw MessageServiceError.messageTooLong
    }
    return payload
  }
}
