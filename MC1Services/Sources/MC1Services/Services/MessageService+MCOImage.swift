import Foundation

extension MessageService {
  enum ChannelTransmission {
    case text(String)
    case mcoImage(Data)
  }

  /// `im4:` goes out as group data with the stored sender name, so a later rename does not
  /// change the payload. Any other text, including an `im3:` picture, goes out as group text.
  func channelTransmission(
    text: String,
    senderNodeName: String?,
    radioID: UUID
  ) async throws -> ChannelTransmission {
    if MCOImageCodec.isChannelDataText(text) {
      return try .mcoImage(channelMCOImagePayload(text, senderName: senderNodeName ?? ""))
    }
    try await rejectIfChannelTextTooLong(text, radioID: radioID)
    return .text(text)
  }

  /// Canonical channel MCOimg text for this radio: `im4:` when it can send group data,
  /// otherwise the `im3:` text as group text. `im4:` has no `im3:` form, so older radios reject it.
  func canonicalChannelMCOImageText(_ text: String, senderName: String, device: DeviceDTO) async throws -> String {
    if device.supportsChannelData {
      let canonical = try MCOImageCodec.text(fromAppPayload: MCOImageCodec.channelAppPayload(fromText: text))
      _ = try channelMCOImagePayload(canonical, senderName: senderName)
      return canonical
    }
    guard !MCOImageCodec.isChannelDataText(text) else {
      throw MessageServiceError.sendFailed("This image needs radio firmware 1.15 or later.")
    }
    guard case .raster = MCOImageCodec.content(from: text) else { throw MCOImageCodecError.damaged }
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    try await rejectIfChannelTextTooLong(trimmed, radioID: device.radioID)
    return trimmed
  }
}

public extension MessageService {
  /// Refreshes a user-initiated MCOimg resend before its queue envelope captures the wire text.
  func prepareMCOImageForResend(_ message: MessageDTO) async throws -> MessageDTO {
    guard message.mcoImage != nil else { return message }
    var refreshed = message
    if message.channelIndex != nil {
      guard let device = try await dataStore.fetchDevice(radioID: message.radioID) else {
        throw MessageServiceError.notConnected
      }
      // The nonce byte keeps its size, so the canonical text's budget check still holds.
      refreshed.text = try await MCOImageCodec.refreshNonce(in: canonicalChannelMCOImageText(
        message.text, senderName: message.senderNodeName ?? "", device: device
      ))
    } else {
      refreshed.text = try MCOImageCodec.refreshNonce(in: message.text)
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
    guard let device = try await dataStore.fetchDevice(radioID: radioID) else {
      throw MessageServiceError.notConnected
    }
    return try await (canonicalChannelMCOImageText(text, senderName: device.nodeName, device: device), device.nodeName)
  }

  internal func channelMCOImagePayload(_ text: String, senderName: String) throws -> Data {
    let payload = try MCOImageTransport.encode(text: text, senderName: senderName)
    guard payload.count <= MCOImageWireBudget.maximumChannelDataBytes else {
      throw MessageServiceError.messageTooLong
    }
    return payload
  }
}
