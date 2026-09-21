import MC1Services

/// Which actions the message sheet offers for a given `MessageDTO`.
struct MessageActionAvailability {
  let canReply: Bool
  let canCopy: Bool
  let canSendAgain: Bool
  let canBlockSender: Bool
  let canSendDM: Bool
  let canShowRepeatDetails: Bool
  let canViewPath: Bool
  let canDelete: Bool
  /// True when the actions sheet shows the path disclosure.
  let showsPathDetail: Bool

  init(message: MessageDTO) {
    canReply = !message.isOutgoing
    canCopy = true
    canSendAgain = message.isOutgoing
    let hasChannelSender = message.isChannelMessage && !message.isOutgoing && message.senderNodeName != nil
    canBlockSender = hasChannelSender
    canSendDM = hasChannelSender
    canShowRepeatDetails = message.isOutgoing && message.heardRepeats > 0
    canViewPath = !message.isOutgoing
      && message.isFloodRouted
      && !(message.pathNodes?.isEmpty ?? true)
    canDelete = true
    showsPathDetail = canViewPath || canShowRepeatDetails || (!message.isOutgoing && message.heardRepeats > 0)
  }
}
