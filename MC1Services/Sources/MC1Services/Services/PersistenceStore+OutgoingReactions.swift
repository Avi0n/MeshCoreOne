import Foundation
import SwiftData

public extension PersistenceStore {
  func admitOutgoingReaction(_ message: MessageDTO) async throws -> MessageDTO? {
    guard message.direction == .outgoing, message.status == .pending,
          let senderName = message.reactionSenderName,
          let details = outgoingReactionDetails(message),
          let target = try outgoingReactionTarget(message),
          details.messageHash == ReactionParser.generateMessageHash(
            text: target.text, timestamp: target.senderTimestamp ?? target.timestamp
          ) else { return nil }
    guard try !reactionExists(messageID: target.id, senderName: senderName, emoji: details.emoji) else {
      return nil
    }

    let targetID: UUID? = target.id
    let sender: String? = senderName
    let predicate = #Predicate<Message> {
      $0.reactionTargetMessageID == targetID && $0.reactionSenderName == sender
    }
    for existing in try modelContext.fetch(FetchDescriptor(predicate: predicate)) {
      guard outgoingReactionDetails(MessageDTO(from: existing))?.emoji == details.emoji else { continue }
      if try hasPendingSend(messageID: existing.id) { return nil }
    }

    try commitPendingRxLogEntries()
    do {
      let pending: PendingSendDTO
      if let contactID = message.contactID {
        pending = PendingSendDTO(
          envelope: DirectMessageEnvelope(messageID: message.id, contactID: contactID, isSingleAttempt: true),
          radioID: message.radioID
        )
      } else if let channelIndex = message.channelIndex {
        pending = PendingSendDTO(
          envelope: ChannelMessageEnvelope(
            messageID: message.id, channelIndex: channelIndex, isResend: false,
            messageText: message.text, messageTimestamp: message.timestamp, localNodeName: senderName
          ),
          radioID: message.radioID
        )
      } else {
        return nil
      }
      modelContext.insert(Message(dto: message))
      _ = try insertPendingSendAssigningSequenceWithoutSaving(pending)
      try modelContext.save()
      return message
    } catch {
      modelContext.rollback()
      throw error
    }
  }

  func completeOutgoingReaction(messageID: UUID) async throws -> ReactionAdmission? {
    try commitPendingRxLogEntries()
    let id = messageID
    let predicate = #Predicate<Message> { $0.id == id }
    guard let message = try modelContext.fetch(FetchDescriptor(predicate: predicate)).first else { return nil }
    do {
      guard let admission = try stageOutgoingReaction(message) else { return nil }
      try modelContext.save()
      return admission
    } catch {
      modelContext.rollback()
      throw error
    }
  }
}

extension PersistenceStore {
  /// Stages the badge with the accepted status so neither can commit alone.
  func stageOutgoingReaction(_ message: Message) throws -> ReactionAdmission? {
    guard message.status == .sent || message.status == .delivered,
          message.direction == .outgoing,
          let senderName = message.reactionSenderName else { return nil }
    let dto = MessageDTO(from: message)
    guard let details = outgoingReactionDetails(dto),
          let target = try outgoingReactionTarget(dto) else { return nil }
    return try admitReactionWithoutSaving(ReactionDTO(
      messageID: target.id,
      emoji: details.emoji,
      senderName: senderName,
      messageHash: details.messageHash,
      rawText: message.text,
      receivedAt: message.createdAt,
      channelIndex: message.channelIndex,
      contactID: message.contactID,
      radioID: message.radioID
    ))
  }

  /// Removing a target also removes its queued wire messages and durable send work.
  @discardableResult
  func deleteOutgoingReactionsWithoutSaving(targetMessageIDs: [UUID]) throws -> Int {
    var remainingTargetIDs = targetMessageIDs
    var visitedTargetIDs: Set<UUID> = []
    var deletedMessageCount = 0
    while let targetMessageID = remainingTargetIDs.popLast() {
      guard visitedTargetIDs.insert(targetMessageID).inserted else { continue }
      let targetID: UUID? = targetMessageID
      let predicate = #Predicate<Message> { $0.reactionTargetMessageID == targetID }
      for message in try modelContext.fetch(FetchDescriptor(predicate: predicate)) {
        let messageID = message.id
        remainingTargetIDs.append(messageID)
        try _deletePendingSendsForMessageIDsWithoutSaving(messageIDs: [messageID])
        try modelContext.delete(model: Reaction.self, where: #Predicate { $0.messageID == messageID })
        modelContext.delete(message)
        deletedMessageCount += 1
      }
    }
    return deletedMessageCount
  }

  private func outgoingReactionDetails(_ message: MessageDTO) -> (emoji: String, messageHash: String)? {
    if message.channelIndex != nil, let parsed = ReactionParser.parse(message.text) {
      return (parsed.emoji, parsed.messageHash)
    }
    if message.contactID != nil, let parsed = ReactionParser.parseDM(message.text) {
      return (parsed.emoji, parsed.messageHash)
    }
    return nil
  }

  private func outgoingReactionTarget(_ message: MessageDTO) throws -> Message? {
    guard let targetID = message.reactionTargetMessageID else { return nil }
    let predicate = #Predicate<Message> { $0.id == targetID }
    guard let target = try modelContext.fetch(FetchDescriptor(predicate: predicate)).first,
          target.radioID == message.radioID,
          target.contactID == message.contactID,
          target.channelIndex == message.channelIndex else { return nil }
    if let contactID = message.contactID {
      guard message.channelIndex == nil,
            try fetchContact(id: contactID)?.radioID == message.radioID else { return nil }
    } else if let channelIndex = message.channelIndex {
      guard try fetchChannel(radioID: message.radioID, index: channelIndex) != nil else { return nil }
    } else {
      return nil
    }
    return target
  }
}
