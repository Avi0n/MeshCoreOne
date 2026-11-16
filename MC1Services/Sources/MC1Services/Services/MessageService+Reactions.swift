import Foundation

extension MessageService {
  func validateOutgoingReaction(_ message: MessageDTO) async throws {
    if message.channelIndex != nil {
      try await rejectIfChannelTextTooLong(message.text, radioID: message.radioID)
    } else if let contactID = message.contactID,
              let contact = try await dataStore.fetchContact(id: contactID),
              contact.radioID == message.radioID {
      try validateDirectMessage(text: message.text, to: contact)
    } else {
      throw MessageServiceError.contactNotFound
    }
  }

  func notifyOutgoingReactionAccepted(messageID: UUID) async {
    do {
      if let result = try await dataStore.completeOutgoingReaction(messageID: messageID) {
        statusEventBroadcaster.yield(.reactionAccepted(messageID: result.messageID, summary: result.summary))
      }
    } catch {
      logger.warning("Reaction update notification deferred messageID=\(messageID): \(String(describing: error))")
    }
  }
}
