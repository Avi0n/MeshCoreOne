import Foundation

public extension PersistenceStore {
  func shouldSuppressChannelMessage(
    _ message: MessageDTO,
    frame: ChannelMessage,
    channel: ChannelDTO,
    scope: ReceiveScope,
    preference: TextRulesPreference?
  ) async throws -> Bool {
    guard scope.isCurrent, scope.radioID == message.radioID else { return true }
    guard let conversationID = channel.textRulesID else { return false }
    guard let current = try fetchChannel(radioID: message.radioID, index: frame.channelIndex),
          current.textRulesID == conversationID else { return true }

    let identity = RecentlyBlockedMessageDTO.Identity(
      radioID: message.radioID,
      conversationID: conversationID,
      senderPrefix: nil,
      senderTimestamp: UInt32(frame.senderTimestamp.timeIntervalSince1970),
      textType: frame.textType,
      wireText: Data(frame.text.utf8)
    )
    if try hasRecentlyBlockedMessage(identity) { return true }

    let kind = TextRulesPreference.channelKind(isPublic: current.isPublicChannel, name: current.name)
    let key = TextRulesPreference.Key(radioID: message.radioID, conversationID: conversationID)
    guard let preference,
          !preference.rules.isEmpty,
          preference.effective(for: kind, key: key, override: current.textRulesOverride).isOn else { return false }

    let fields = ChannelMessageFormat.originalFields(frame.text)
    let isReaction = ReactionParser.isReactionText(message.text, isDM: false)
    guard let match = preference.firstMatch(body: isReaction ? nil : fields.body, sender: fields.sender) else {
      return false
    }
    if !isReaction {
      try recordRecentlyBlockedMessage(RecentlyBlockedMessageDTO(
        identity: identity,
        arrivedAt: message.createdAt,
        body: fields.body,
        sender: fields.sender,
        conversationName: current.name,
        radioName: fetchDevice(radioID: message.radioID)?.nodeName,
        match: match
      ))
    }
    return true
  }
}
