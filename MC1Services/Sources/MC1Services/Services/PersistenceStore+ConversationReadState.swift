import Foundation
import SwiftData

public extension PersistenceStore {
  private static let reminderUnreadCount = 1

  func setContactReadState(_ contactID: UUID, isRead: Bool) throws {
    let targetID = contactID
    let predicate = #Predicate<Contact> { $0.id == targetID }
    var descriptor = FetchDescriptor<Contact>(predicate: predicate)
    descriptor.fetchLimit = 1
    guard let contact = try modelContext.fetch(descriptor).first else {
      throw PersistenceStoreError.contactNotFound
    }

    contact.unreadCount = isRead ? 0 : max(contact.unreadCount, Self.reminderUnreadCount)
    if isRead { contact.unreadMentionCount = 0 }
    try modelContext.save()
  }

  func setChannelReadState(_ channelID: UUID, isRead: Bool) throws {
    let targetID = channelID
    let predicate = #Predicate<Channel> { $0.id == targetID }
    var descriptor = FetchDescriptor<Channel>(predicate: predicate)
    descriptor.fetchLimit = 1
    guard let channel = try modelContext.fetch(descriptor).first else {
      throw PersistenceStoreError.channelNotFound
    }

    channel.unreadCount = isRead ? 0 : max(channel.unreadCount, Self.reminderUnreadCount)
    if isRead { channel.unreadMentionCount = 0 }
    try modelContext.save()
  }

  func setRoomReadState(_ sessionID: UUID, isRead: Bool) throws {
    let targetID = sessionID
    let predicate = #Predicate<RemoteNodeSession> { $0.id == targetID }
    var descriptor = FetchDescriptor<RemoteNodeSession>(predicate: predicate)
    descriptor.fetchLimit = 1
    guard let session = try modelContext.fetch(descriptor).first else {
      throw PersistenceStoreError.remoteNodeSessionNotFound
    }

    session.unreadCount = isRead ? 0 : max(session.unreadCount, Self.reminderUnreadCount)
    try modelContext.save()
  }
}
