import MC1Services
import UserNotifications

extension ChatViewModelReadStateTests {
  @MainActor
  final class Recorder {
    var badges: [Int] = []
    var removed: [String] = []
    var requests: [UNNotificationRequest] = []
    var loads = 0
    var snapshotHook: (@MainActor () async -> Void)?

    var operations: NotificationService.SystemOperations {
      .init(
        setBadgeCount: { self.badges.append($0) },
        deliveredRequests: {
          self.loads += 1
          let snapshot = self.requests
          await self.snapshotHook?()
          return snapshot
        },
        removeDeliveredNotifications: { identifiers in
          self.removed.append(contentsOf: identifiers)
          self.requests.removeAll { identifiers.contains($0.identifier) }
        }
      )
    }
  }
}
