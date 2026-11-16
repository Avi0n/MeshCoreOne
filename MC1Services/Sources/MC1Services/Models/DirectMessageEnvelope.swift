import Foundation

/// A queued DM whose Contact is fetched again when the send is admitted.
public struct DirectMessageEnvelope: Sendable {
  public let messageID: UUID
  public let contactID: UUID
  /// Explicit user resends increment `sendCount`; queue retries do not.
  public let isResend: Bool
  public let isSingleAttempt: Bool

  public init(messageID: UUID, contactID: UUID, isResend: Bool = false, isSingleAttempt: Bool = false) {
    self.messageID = messageID
    self.contactID = contactID
    self.isResend = isResend
    self.isSingleAttempt = isSingleAttempt
  }
}
