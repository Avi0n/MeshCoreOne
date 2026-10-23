import Foundation
@testable import MC1Services
import Testing

@Suite("Notification badge count")
@MainActor
struct NotificationBadgeCountTests {
  @Test(arguments: [
    String?.none,
    AppStorageKey.notificationBadgeEnabled.rawValue,
    AppStorageKey.notifyContactMessages.rawValue,
    AppStorageKey.notifyChannelMessages.rawValue,
    AppStorageKey.notifyRoomMessages.rawValue
  ])
  func `badge calculation honors disabled preferences`(disabledKeyName: String?) throws {
    let suiteName = "NotificationBadgeCountTests.\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let disabledKey = disabledKeyName.flatMap(AppStorageKey.init(rawValue:))
    if let disabledKey { defaults.set(false, forKey: disabledKey.rawValue) }
    let expected = switch disabledKey {
    case .notificationBadgeEnabled: 0
    case .notifyContactMessages: 8
    case .notifyChannelMessages: 7
    case .notifyRoomMessages: 5
    default: 10
    }

    let count = NotificationService.badgeCount(
      unreadCounts: (contacts: 2, channels: 3, rooms: 5),
      preferences: NotificationPreferences(defaults: defaults)
    )

    #expect(count == expected)
  }
}
