import Foundation
import SwiftData

public extension PersistenceStore {
  func admitDirectMessage(
    _ message: MessageDTO,
    wireTextType: UInt8,
    contact capturedContact: ContactDTO?,
    scope: ReceiveScope,
    preference: TextRulesPreference?
  ) async throws -> DirectMessageAdmission {
    guard scope.radioID == message.radioID, scope.isCurrent else { return .discarded }
    let contact: ContactDTO?
    if let capturedContact {
      guard let current = try fetchCurrentDirectMessageContact(capturedContact, scope: scope) else {
        return .discarded
      }
      contact = current
    } else {
      contact = nil
    }

    let wireTimestamp = message.senderTimestamp ?? message.timestamp
    let body = Data(message.text.utf8)
    if let saved = try savedDirectMessage(message, timestamp: wireTimestamp, body: body) {
      return .replay(saved)
    }

    let identity = RecentlyBlockedMessageDTO.Identity(
      radioID: message.radioID,
      conversationID: nil,
      senderPrefix: message.senderKeyPrefix,
      senderTimestamp: wireTimestamp,
      textType: wireTextType,
      wireText: body
    )
    if try hasRecentlyBlockedMessage(identity) { return .discarded }

    let isReaction = contact != nil && ReactionParser.isReactionText(message.text, isDM: true)
    let key = contact?.textRulesID.map {
      TextRulesPreference.Key(radioID: message.radioID, conversationID: $0)
    }
    if let preference,
       !preference.rules.isEmpty,
       contact?.isBlocked != true,
       preference.effective(for: .directMessage, key: key, override: contact?.textRulesOverride ?? .inherit).isOn {
      let sender = try uniqueDirectMessageSender(message)
      if let match = preference.firstMatch(body: isReaction ? nil : message.text, sender: sender?.displayName) {
        if !isReaction {
          try recordRecentlyBlockedMessage(RecentlyBlockedMessageDTO(
            identity: identity,
            arrivedAt: message.createdAt,
            body: message.text,
            sender: sender?.displayName,
            conversationName: contact?.displayName,
            radioName: fetchDevice(radioID: message.radioID)?.nodeName,
            match: match,
            contactID: sender?.id
          ))
        }
        return .discarded
      }
    }

    if isReaction, let contact { return .reaction(contact) }
    try saveMessage(message)
    return .saved(contact)
  }

  func currentDirectMessageContact(_ contact: ContactDTO, scope: ReceiveScope) async throws -> ContactDTO? {
    try fetchCurrentDirectMessageContact(contact, scope: scope)
  }

  func materializeContactForDirectMessage(
    from frame: ContactFrame,
    receivedAt: Date,
    scope: ReceiveScope
  ) async throws -> ContactDTO? {
    guard scope.isCurrent else { return nil }

    let result = try insertContactIfAbsent(radioID: scope.radioID, from: frame)
    _ = try touchContactHeard(radioID: scope.radioID, publicKey: frame.publicKey, at: receivedAt)
    _ = try upsertDiscoveredNode(radioID: scope.radioID, from: frame)
    return try fetchContact(id: result.id)
  }

  func touchDirectMessageContact(_ contact: ContactDTO, scope: ReceiveScope, at date: Date) async throws {
    guard try fetchCurrentDirectMessageContact(contact, scope: scope) != nil else { return }
    _ = try touchContactHeard(radioID: scope.radioID, publicKey: contact.publicKey, at: date)
  }

  func updateContactAfterDirectMessage(
    _ message: MessageDTO,
    contact: ContactDTO,
    scope: ReceiveScope,
    isViewingContact: Bool
  ) async throws -> ContactDTO? {
    guard let current = try fetchCurrentDirectMessageContact(contact, scope: scope) else { return nil }
    try updateContactLastMessage(contactID: current.id, date: message.createdAt)
    if !isViewingContact, !current.isBlocked {
      try incrementUnreadCount(contactID: current.id)
      if message.containsSelfMention {
        try incrementUnreadMentionCount(contactID: current.id)
      }
    }
    return current
  }

  func admitDirectMessageReaction(
    _ reaction: ReactionDTO,
    contact: ContactDTO,
    scope: ReceiveScope
  ) async throws -> ReactionAdmission? {
    guard reaction.contactID == contact.id, reaction.radioID == scope.radioID,
          try fetchCurrentDirectMessageContact(contact, scope: scope) != nil else {
      return nil
    }
    return try admitReaction(reaction)
  }
}

private extension PersistenceStore {
  static let directMessageSenderPrefixLength = 6

  func fetchCurrentDirectMessageContact(_ contact: ContactDTO, scope: ReceiveScope) throws -> ContactDTO? {
    guard scope.isCurrent, contact.radioID == scope.radioID,
          let current = try fetchContact(id: contact.id),
          current.radioID == scope.radioID, current.textRulesID == contact.textRulesID else {
      return nil
    }
    return current
  }

  func uniqueDirectMessageSender(_ message: MessageDTO) throws -> ContactDTO? {
    guard let prefix = message.senderKeyPrefix, prefix.count == Self.directMessageSenderPrefixLength else {
      return nil
    }
    let radioID = message.radioID
    let descriptor = FetchDescriptor(predicate: #Predicate<Contact> { $0.radioID == radioID })
    let matches = try modelContext.fetch(descriptor).lazy.filter { $0.publicKey.starts(with: prefix) }.prefix(2)
    return matches.count == 1 ? matches.first.map { ContactDTO(from: $0) } : nil
  }

  func savedDirectMessage(_ incoming: MessageDTO, timestamp: UInt32, body: Data) throws -> MessageDTO? {
    guard let prefix = incoming.senderKeyPrefix, prefix.count == Self.directMessageSenderPrefixLength else {
      return nil
    }
    let radioID = incoming.radioID
    let incomingDirection = MessageDirection.incoming.rawValue
    let type = incoming.textType.rawValue
    let senderPrefix: Data? = prefix
    let senderTimestamp: UInt32? = timestamp

    if let key = incoming.deduplicationKey,
       let saved = try fetchMessage(deduplicationKey: key, radioID: radioID),
       saved.direction == .incoming, saved.channelIndex == nil,
       saved.senderKeyPrefix == prefix, saved.textType == incoming.textType,
       (saved.senderTimestamp ?? (saved.timestampCorrected ? nil : saved.timestamp)) == timestamp,
       saved.text.utf8.elementsEqual(body) {
      return saved
    }

    let corrected = #Predicate<Message> {
      $0.radioID == radioID && $0.senderKeyPrefix == senderPrefix && $0.senderTimestamp == senderTimestamp &&
        $0.directionRawValue == incomingDirection && $0.channelIndex == nil && $0.textTypeRawValue == type
    }
    let original = #Predicate<Message> {
      $0.radioID == radioID && $0.senderKeyPrefix == senderPrefix && $0.timestamp == timestamp &&
        $0.senderTimestamp == nil && !$0.timestampCorrected &&
        $0.directionRawValue == incomingDirection && $0.channelIndex == nil && $0.textTypeRawValue == type
    }
    for predicate in [corrected, original] {
      if let row = try modelContext.fetch(FetchDescriptor(predicate: predicate)).first(where: { $0.text.utf8.elementsEqual(body) }) {
        return MessageDTO(from: row)
      }
    }
    return nil
  }
}
