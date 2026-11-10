import Foundation

/// A queued direct message whose attempt bytes were already handed to the radio.
/// The service hashes acknowledgement codes from these fields. The store does not.
public struct QueuedDirectMessageWireAttempt: Sendable, Equatable {
  public let pendingSendID: UUID
  public let messageID: UUID
  public let timestamp: UInt32
  public let text: String
  /// Attempt bytes `0..<consumedAttempts` were handed to the radio for `timestamp`.
  public let consumedAttempts: UInt8

  public init(
    pendingSendID: UUID,
    messageID: UUID,
    timestamp: UInt32,
    text: String,
    consumedAttempts: UInt8
  ) {
    self.pendingSendID = pendingSendID
    self.messageID = messageID
    self.timestamp = timestamp
    self.text = text
    self.consumedAttempts = consumedAttempts
  }
}
