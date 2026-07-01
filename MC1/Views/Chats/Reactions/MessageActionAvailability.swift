import MC1Services

/// Determines which message actions are available based on message state.
/// Extracted for testability and reuse across UI components.
struct MessageActionAvailability {
  let canReply: Bool
  let canCopy: Bool
  let canSendAgain: Bool
  let canBlockSender: Bool
  let canSendDM: Bool
  let canShowRepeatDetails: Bool
  let canViewPath: Bool
  let canDelete: Bool

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
  }
}
