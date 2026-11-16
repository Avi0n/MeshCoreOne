import Foundation
import SwiftData

extension PersistenceStore {
  func receiveRoomPost(
    _ post: RoomMessageDTO,
    in room: RemoteNodeSessionDTO,
    scope: ReceiveScope,
    preference: TextRulesPreference?
  ) throws -> (message: RoomMessageDTO?, recovered: Bool) {
    guard scope.isCurrent, scope.radioID == room.radioID else { return (nil, false) }

    let sessionID = room.id
    var roomDescriptor = FetchDescriptor<RemoteNodeSession>(predicate: #Predicate { $0.id == sessionID })
    roomDescriptor.fetchLimit = 1
    guard let session = try modelContext.fetch(roomDescriptor).first,
          session.radioID == room.radioID,
          session.textRulesID == room.textRulesID else { return (nil, false) }

    let key = post.deduplicationKey
    let candidates = try modelContext.fetch(FetchDescriptor<RoomMessage>(predicate: #Predicate {
      $0.sessionID == sessionID && $0.deduplicationKey == key
    }))
    let alreadySaved = candidates.contains {
      $0.timestamp == post.timestamp &&
        $0.authorKeyPrefix == post.authorKeyPrefix &&
        $0.text.utf8.elementsEqual(post.text.utf8)
    }
    let identity = RecentlyBlockedMessageDTO.Identity(
      radioID: room.radioID,
      conversationID: room.textRulesID,
      senderPrefix: post.authorKeyPrefix,
      senderTimestamp: post.timestamp,
      textType: TextType.signedPlain.rawValue,
      wireText: Data(post.text.utf8)
    )
    if try alreadySaved || hasRecentlyBlockedMessage(identity) {
      return try (nil, recoverRoom(session))
    }

    let displayName = try findContactNameByKeyPrefix(post.authorKeyPrefix)
    if !post.isFromSelf,
       let preference,
       let conversationID = room.textRulesID,
       preference.effective(
         for: .room,
         key: .init(radioID: room.radioID, conversationID: conversationID),
         override: session.textRulesOverride
       ).isOn {
      let matchingName = try uniqueRoomAuthorName(post.authorKeyPrefix, radioID: room.radioID)
      if let match = preference.firstMatch(body: post.text, sender: matchingName) {
        let radioName = try fetchDevice(radioID: room.radioID)?.nodeName
        try recordRecentlyBlockedMessage(RecentlyBlockedMessageDTO(
          identity: identity,
          arrivedAt: post.createdAt,
          body: post.text,
          sender: matchingName ?? displayName,
          conversationName: session.name,
          radioName: radioName,
          match: match
        ))
        return try (nil, recoverRoom(session))
      }
    }

    try commitPendingRxLogEntries()
    let recovered = !session.isConnected
    let message = RoomMessage(dto: post)
    message.authorName = displayName
    modelContext.insert(message)
    session.isConnected = true
    session.lastSyncTimestamp = max(session.lastSyncTimestamp, post.timestamp)
    session.lastMessageDate = post.createdAt
    if !post.isFromSelf { session.unreadCount += 1 }
    do {
      try modelContext.save()
      return (RoomMessageDTO(from: message), recovered)
    } catch {
      modelContext.rollback()
      throw error
    }
  }

  private func recoverRoom(_ room: RemoteNodeSession) throws -> Bool {
    guard !room.isConnected else { return false }
    try commitPendingRxLogEntries()
    room.isConnected = true
    do {
      try modelContext.save()
      return true
    } catch {
      modelContext.rollback()
      throw error
    }
  }

  private func uniqueRoomAuthorName(_ prefix: Data, radioID: UUID) throws -> String? {
    let descriptor = FetchDescriptor<Contact>(predicate: #Predicate { $0.radioID == radioID })
    let matches = try modelContext.fetch(descriptor).lazy
      .filter { $0.publicKey.prefix(prefix.count) == prefix }
      .prefix(2)
    guard matches.count == 1 else { return nil }
    return matches.first?.displayName
  }
}
