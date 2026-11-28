import Foundation

/// A queued DM whose Contact is fetched again when the send is admitted.
public struct DirectMessageEnvelope: Sendable {
  public let messageID: UUID
  public let contactID: UUID
  /// Explicit user resends increment `sendCount`; queue retries do not.
  public let isResend: Bool
  public let isSingleAttempt: Bool
  /// Backoff rounds allowed after the normal retry budget is spent. `0` fails as before.
  public let maxBackoffRounds: Int

  public init(
    messageID: UUID,
    contactID: UUID,
    isResend: Bool = false,
    isSingleAttempt: Bool = false,
    maxBackoffRounds: Int = 0
  ) {
    self.maxBackoffRounds = maxBackoffRounds
    self.messageID = messageID
    self.contactID = contactID
    self.isResend = isResend
    self.isSingleAttempt = isSingleAttempt
  }
}
