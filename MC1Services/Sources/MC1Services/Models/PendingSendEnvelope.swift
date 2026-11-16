import Foundation

public extension PendingSendDTO {
  /// ACK-owned rows retain delivery tracking without replaying the DM.
  func directMessageEnvelope() -> DirectMessageEnvelope? {
    guard kind == .dm, ackDeadline == nil, let contactID else { return nil }
    return DirectMessageEnvelope(
      messageID: messageID, contactID: contactID, isResend: isResend, isSingleAttempt: isSingleAttempt
    )
  }

  func channelMessageEnvelope() -> ChannelMessageEnvelope? {
    guard kind == .channel, let channelIndex else { return nil }
    return ChannelMessageEnvelope(
      messageID: messageID,
      channelIndex: channelIndex,
      isResend: isResend,
      messageText: messageText,
      messageTimestamp: messageTimestamp,
      localNodeName: localNodeName
    )
  }
}

/// `PersistenceStore` assigns the sequence atomically so concurrent enqueues remain ordered.
public extension PendingSendDTO {
  init(
    id: UUID = UUID(),
    envelope: DirectMessageEnvelope,
    radioID: UUID,
    enqueuedAt: Date = Date(),
    ackCodes: [Data]? = nil,
    ackSentAt: Date? = nil,
    ackDeadline: Date? = nil
  ) {
    // The DM message row owns its text and wire timestamp; the envelope retains only send policy.
    self.init(
      id: id,
      radioID: radioID,
      messageID: envelope.messageID,
      kind: .dm,
      contactID: envelope.contactID,
      channelIndex: nil,
      isResend: envelope.isResend,
      messageText: "",
      messageTimestamp: 0,
      localNodeName: nil,
      sequence: 0,
      enqueuedAt: enqueuedAt,
      ackCodes: ackCodes,
      ackSentAt: ackSentAt,
      ackDeadline: ackDeadline,
      isSingleAttempt: envelope.isSingleAttempt
    )
  }

  init(
    id: UUID = UUID(),
    envelope: ChannelMessageEnvelope,
    radioID: UUID,
    enqueuedAt: Date = Date()
  ) {
    // A first resend replaces this timestamp; reaction indexing uses the post-send value.
    self.init(
      id: id,
      radioID: radioID,
      messageID: envelope.messageID,
      kind: .channel,
      contactID: nil,
      channelIndex: envelope.channelIndex,
      isResend: envelope.isResend,
      messageText: envelope.messageText,
      messageTimestamp: envelope.messageTimestamp,
      localNodeName: envelope.localNodeName,
      sequence: 0,
      enqueuedAt: enqueuedAt
    )
  }
}
