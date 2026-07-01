import Foundation

/// Filter options for the Chats list view
enum ChatFilter: String, CaseIterable, Identifiable {
  case all
  case unread
  case directMessages
  case channels
  case rooms

  var id: String {
    rawValue
  }

  var localizedName: String {
    switch self {
    case .all: L10n.Chats.Chats.Filter.all
    case .unread: L10n.Chats.Chats.Filter.unread
    case .directMessages: L10n.Chats.Chats.Filter.directMessages
    case .channels: L10n.Chats.Chats.Filter.channels
    case .rooms: L10n.Chats.Chats.Filter.rooms
    }
  }
}
