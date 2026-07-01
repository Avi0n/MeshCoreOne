import Foundation

/// Session operations for sending direct and channel messages.
public protocol MessagingSessionOps: Actor {
  /// Returns the device's self info after session start.
  ///
  /// Populated once the session handshake completes; `nil` before that.
  var currentSelfInfo: SelfInfo? { get }

  /// Sends a direct message to a contact.
  ///
  /// - Parameters:
  ///   - destination: The recipient's public key (6-byte prefix).
  ///   - text: The message text to send.
  ///   - timestamp: The timestamp of the message.
  ///   - attempt: Retry attempt counter (0 for first attempt). Included in ACK hash.
  /// - Returns: A `MessageSentInfo` object containing information about the sent message, including the ACK code.
  /// - Throws: `MeshCoreError` if the message fails to send or the device returns an error.
  func sendMessage(
    to destination: Data,
    text: String,
    timestamp: Date,
    attempt: UInt8
  ) async throws -> MessageSentInfo

  /// Sends a message to a channel.
  ///
  /// - Parameters:
  ///   - channel: The channel index (0-7).
  ///   - text: The message text to send.
  ///   - timestamp: The timestamp of the message.
  /// - Throws: `MeshCoreError` if the channel message fails to send.
  func sendChannelMessage(
    channel: UInt8,
    text: String,
    timestamp: Date
  ) async throws
}

// MARK: - Default Implementations

public extension MessagingSessionOps {
  /// Sends a direct message with default attempt counter of 0.
  func sendMessage(
    to destination: Data,
    text: String,
    timestamp: Date
  ) async throws -> MessageSentInfo {
    try await sendMessage(to: destination, text: text, timestamp: timestamp, attempt: 0)
  }
}
