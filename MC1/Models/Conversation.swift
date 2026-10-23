import Foundation
import MC1Services

/// Represents a conversation in the chat list - direct chat, channel, or room
enum Conversation: Identifiable, Hashable {
  case direct(ContactDTO)
  case channel(ChannelDTO)
  case room(RemoteNodeSessionDTO)

  var id: UUID {
    switch self {
    case let .direct(contact):
      contact.id
    case let .channel(channel):
      channel.id
    case let .room(session):
      session.id
    }
  }

  var displayName: String {
    switch self {
    case let .direct(contact):
      contact.displayName
    case let .channel(channel):
      channel.displayName
    case let .room(session):
      session.name
    }
  }

  var lastMessageDate: Date? {
    switch self {
    case let .direct(contact):
      contact.lastMessageDate
    case let .channel(channel):
      channel.lastMessageDate
    case let .room(session):
      session.lastMessageDate
    }
  }

  var unreadCount: Int {
    switch self {
    case let .direct(contact):
      contact.unreadCount
    case let .channel(channel):
      channel.unreadCount
    case let .room(session):
      session.unreadCount
    }
  }

  /// Contacts and channels are unread when either count is positive. Rooms have no mention count.
  var isUnread: Bool {
    switch self {
    case let .direct(contact):
      contact.unreadCount > 0 || contact.unreadMentionCount > 0
    case let .channel(channel):
      channel.unreadCount > 0 || channel.unreadMentionCount > 0
    case let .room(session):
      session.unreadCount > 0
    }
  }

  var notificationLevel: NotificationLevel {
    switch self {
    case let .direct(contact):
      contact.isMuted ? .muted : .all
    case let .channel(channel):
      channel.notificationLevel
    case let .room(session):
      session.notificationLevel
    }
  }

  var isMuted: Bool {
    notificationLevel == .muted
  }

  var isPinned: Bool {
    switch self {
    case let .direct(contact):
      contact.isPinned
    case let .channel(channel):
      channel.isPinned
    case let .room(session):
      session.isPinned
    }
  }
}
