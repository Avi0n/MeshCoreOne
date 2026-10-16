import Foundation
import SwiftData

public extension PersistenceStore {
  /// Collects conversation identities that have at least one unseen outgoing
  /// `.failed` send for `radioID`. Channel IDs are resolved under this radio.
  /// Rooms are fetched by this radio's session IDs.
  func fetchFailedSendConversationKeys(radioID: UUID) throws -> FailedSendConversationKeys {
    let targetRadioID = radioID
    let failedStatus = MessageStatus.failed.rawValue
    let outgoing = MessageDirection.outgoing.rawValue

    let messagePredicate = #Predicate<Message> { message in
      message.radioID == targetRadioID
        && message.statusRawValue == failedStatus
        && message.directionRawValue == outgoing
        && message.failureSeen == false
    }
    let failedMessages = try modelContext.fetch(FetchDescriptor(predicate: messagePredicate))

    var contactIDs: Set<UUID> = []
    var channelIndexes: Set<UInt8> = []
    contactIDs.reserveCapacity(failedMessages.count)
    channelIndexes.reserveCapacity(failedMessages.count)
    for message in failedMessages {
      if let contactID = message.contactID {
        contactIDs.insert(contactID)
      }
      if let channelIndex = message.channelIndex {
        channelIndexes.insert(channelIndex)
      }
    }

    var channelIDs: Set<UUID> = []
    if !channelIndexes.isEmpty {
      let channels = try fetchChannels(radioID: targetRadioID)
      for channel in channels where channelIndexes.contains(channel.index) {
        channelIDs.insert(channel.id)
      }
    }

    let sessionPredicate = #Predicate<RemoteNodeSession> { session in
      session.radioID == targetRadioID
    }
    let sessions = try modelContext.fetch(FetchDescriptor(predicate: sessionPredicate))
    let radioSessionIDs = Set(sessions.map(\.id))
    guard !radioSessionIDs.isEmpty else {
      return FailedSendConversationKeys(
        contactIDs: contactIDs,
        channelIDs: channelIDs,
        roomSessionIDs: []
      )
    }

    let sessionIDs = Array(radioSessionIDs)
    let failedRoomMessages = try fetchInChunks(keys: sessionIDs) { chunk in
      let sessionChunk = chunk
      let predicate = #Predicate<RoomMessage> { message in
        sessionChunk.contains(message.sessionID)
          && message.statusRawValue == failedStatus
          && message.isFromSelf
          && message.failureSeen == false
      }
      return try modelContext.fetch(FetchDescriptor(predicate: predicate))
    }
    let roomSessionIDs = Set(failedRoomMessages.map(\.sessionID))

    return FailedSendConversationKeys(
      contactIDs: contactIDs,
      channelIDs: channelIDs,
      roomSessionIDs: roomSessionIDs
    )
  }

  /// Marks current outgoing `.failed` DMs in `contactID` as seen by the user.
  func markFailedSendsSeen(contactID: UUID) throws {
    let targetContactID: UUID? = contactID
    let failedStatus = MessageStatus.failed.rawValue
    let outgoing = MessageDirection.outgoing.rawValue
    let predicate = #Predicate<Message> { message in
      message.contactID == targetContactID
        && message.statusRawValue == failedStatus
        && message.directionRawValue == outgoing
        && message.failureSeen == false
    }
    try markMessagesFailureSeen(predicate)
  }

  /// Marks current outgoing `.failed` channel rows as seen by the user.
  func markFailedSendsSeen(radioID: UUID, channelIndex: UInt8) throws {
    let targetRadioID = radioID
    let targetIndex: UInt8? = channelIndex
    let failedStatus = MessageStatus.failed.rawValue
    let outgoing = MessageDirection.outgoing.rawValue
    let predicate = #Predicate<Message> { message in
      message.radioID == targetRadioID
        && message.channelIndex == targetIndex
        && message.statusRawValue == failedStatus
        && message.directionRawValue == outgoing
        && message.failureSeen == false
    }
    try markMessagesFailureSeen(predicate)
  }

  /// Marks current self `.failed` room rows as seen by the user.
  func markFailedSendsSeen(roomSessionID: UUID) throws {
    let targetSessionID = roomSessionID
    let failedStatus = MessageStatus.failed.rawValue
    let predicate = #Predicate<RoomMessage> { message in
      message.sessionID == targetSessionID
        && message.statusRawValue == failedStatus
        && message.isFromSelf
        && message.failureSeen == false
    }
    let messages = try modelContext.fetch(FetchDescriptor(predicate: predicate))
    guard !messages.isEmpty else { return }
    for message in messages {
      message.failureSeen = true
    }
    try modelContext.save()
  }

  private func markMessagesFailureSeen(_ predicate: Predicate<Message>) throws {
    let messages = try modelContext.fetch(FetchDescriptor(predicate: predicate))
    guard !messages.isEmpty else { return }
    for message in messages {
      message.failureSeen = true
    }
    try modelContext.save()
  }
}
