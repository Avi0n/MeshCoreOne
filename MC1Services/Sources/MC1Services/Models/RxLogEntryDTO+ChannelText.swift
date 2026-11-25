import Foundation

public extension RxLogEntryDTO {
  /// Sender node name the firmware prepends to decrypted channel text.
  var channelSenderName: String? {
    guard payloadType == .groupText, let decodedText else { return nil }
    guard let name = ChannelMessageFormat.parse(decodedText)?.senderName, !name.isEmpty else { return nil }
    return name
  }

  /// Decoded text without the channel sender prefix.
  var messageBody: String? {
    guard payloadType == .groupText, let decodedText else { return decodedText }
    return ChannelMessageFormat.parse(decodedText)?.messageText ?? decodedText
  }
}
