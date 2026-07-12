import Foundation
import SwiftData

public extension PersistenceStore {
  // MARK: - Badge Count Support

  /// Efficiently calculate total unread counts for badge display
  /// Returns tuple of (contactUnread, channelUnread, roomUnread) for preference-aware calculation
  /// Optimization: Only fetches entities with unread > 0 to minimize memory usage
  func getTotalUnreadCounts(radioID: UUID) throws -> (contacts: Int, channels: Int, rooms: Int) {
    let targetRadioID = radioID

    // Only fetch non-blocked, non-muted, non-repeater contacts with unread messages for this device.
    // Repeater contacts are filtered out of the chats list (ChatViewModel), so unread on them
    // is unreachable to the user and must not inflate the badge.
    let repeaterContactRaw = ContactType.repeater.rawValue
    let contactPredicate = #Predicate<Contact> {
      $0.radioID == targetRadioID &&
        $0.unreadCount > 0 &&
        !$0.isMuted &&
        !$0.isBlocked &&
        $0.typeRawValue != repeaterContactRaw
    }
    let contactDescriptor = FetchDescriptor<Contact>(predicate: contactPredicate)
    let contactsWithUnread = try modelContext.fetch(contactDescriptor)
    let contactTotal = contactsWithUnread.reduce(0) { $0 + $1.unreadCount }

    // Channels: exclude muted, include if unreadCount > 0 OR unreadMentionCount > 0
    let mutedRawValue = NotificationLevel.muted.rawValue
    let channelPredicate = #Predicate<Channel> {
      $0.radioID == targetRadioID &&
        $0.notificationLevelRawValue != mutedRawValue &&
        ($0.unreadCount > 0 || $0.unreadMentionCount > 0)
    }
    let channelDescriptor = FetchDescriptor<Channel>(predicate: channelPredicate)
    let channelsWithUnread = try modelContext.fetch(channelDescriptor)
    let channelTotal = channelsWithUnread.reduce(0) { total, channel in
      if channel.notificationLevel == .mentionsOnly {
        return total + channel.unreadMentionCount
      }
      return total + channel.unreadCount
    }

    // Rooms: only include room-server sessions; repeater-role admin sessions are filtered out
    // of the chats list and would otherwise be unreachable badge contributors.
    let roomServerRoleRaw = RemoteNodeRole.roomServer.rawValue
    let roomPredicate = #Predicate<RemoteNodeSession> {
      $0.radioID == targetRadioID &&
        $0.notificationLevelRawValue != mutedRawValue &&
        $0.unreadCount > 0 &&
        $0.roleRawValue == roomServerRoleRaw
    }
    let roomDescriptor = FetchDescriptor<RemoteNodeSession>(predicate: roomPredicate)
    let roomsWithUnread = try modelContext.fetch(roomDescriptor)
    let roomTotal = roomsWithUnread.reduce(0) { $0 + $1.unreadCount }

    return (contacts: contactTotal, channels: channelTotal, rooms: roomTotal)
  }

  /// Get total unread count for a contact
  func getUnreadCount(contactID: UUID) throws -> Int {
    let targetID = contactID
    let predicate = #Predicate<Contact> { contact in
      contact.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1
    return try modelContext.fetch(descriptor).first?.unreadCount ?? 0
  }

  /// Get total unread count for a channel
  func getChannelUnreadCount(channelID: UUID) throws -> Int {
    let targetID = channelID
    let predicate = #Predicate<Channel> { channel in
      channel.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1
    return try modelContext.fetch(descriptor).first?.unreadCount ?? 0
  }

  // MARK: - Link Preview Data

  /// Fetches link preview data by URL
  func fetchLinkPreview(url: String) throws -> LinkPreviewDataDTO? {
    let urlToFind = url
    let predicate = #Predicate<LinkPreviewData> { preview in
      preview.url == urlToFind
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    guard let preview = try modelContext.fetch(descriptor).first else {
      return nil
    }
    return LinkPreviewDataDTO(from: preview)
  }

  /// Saves or updates link preview data
  func saveLinkPreview(_ dto: LinkPreviewDataDTO) throws {
    let urlToFind = dto.url
    let predicate = #Predicate<LinkPreviewData> { preview in
      preview.url == urlToFind
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    if let existing = try modelContext.fetch(descriptor).first {
      // Update existing
      existing.title = dto.title
      existing.imageData = dto.imageData
      existing.iconData = dto.iconData
      existing.imageWidth = dto.imageWidth
      existing.imageHeight = dto.imageHeight
      existing.fetchedAt = dto.fetchedAt
    } else {
      // Insert new
      let preview = LinkPreviewData(
        url: dto.url,
        title: dto.title,
        imageData: dto.imageData,
        iconData: dto.iconData,
        imageWidth: dto.imageWidth,
        imageHeight: dto.imageHeight,
        fetchedAt: dto.fetchedAt
      )
      modelContext.insert(preview)
    }
    try modelContext.save()
  }
}
